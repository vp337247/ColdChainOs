# ColdChainOS — Capacity Estimation & Sizing Model

## 1. Purpose of This Document
In a Senior Software Engineer or Solution Architect interview, **capacity estimation is not an academic math quiz**—it is the direct justification for your system design decisions:
* It tells you whether you need a single relational database or a distributed time-series database.
* It dictates whether synchronous HTTP ingestion is sufficient or whether an asynchronous message queue (Kafka) is required.
* It dictates memory sizing for caches (Redis) and disk sizing for database volumes.
* It proves why you should *not* prematurely over-engineer components that experience trivial load.

---

## 2. Baseline Assumptions (V1 Production Target)

| Parameter | Baseline Value | Description / Note |
| :--- | :--- | :--- |
| **Number of Tenants** | **50** organizations | Medium pharma importers, food distributors, 3PLs. |
| **Daily New Shipments** | **1,000** shipments/day | Across all 50 tenants (avg 20 shipments/tenant/day). |
| **Average Shipment Duration** | **3 days** (72 hours) | Mixed domestic (1 day) and international (5-7 days). |
| **Active Shipments in Transit** | **3,000** concurrent | $1,000 \text{ shipments/day} \times 3 \text{ days}$ active window. |
| **IoT Sensors per Shipment** | **1.5** average | Pharma often carries 2 redundant loggers; food carries 1. |
| **Active Concurrent Sensors** | **4,500** active devices | $3,000 \text{ shipments} \times 1.5 \text{ sensors}$. |
| **Telemetry Reporting Cadence** | **Every 60 seconds** | High-precision tracking while in transit. |
| **Active Business Users** | **1,000** daily active users | Dispatchers, warehouse staff, QA, admins. |
| **Documents per Shipment** | **4** documents | Bill of Lading, CoA, Customs release, Packing list. |
| **Average Document File Size** | **1.5 MB** | Scanned PDFs and signed manifests. |

---

## 3. Step-by-Step Volume & Throughput Calculations

### 3.1 Telemetry Ingestion Throughput (IoT Events)

1. **Total Daily Readings**:
   $$\text{Readings/sensor/day} = \frac{24 \times 3600 \text{ seconds}}{60 \text{ seconds}} = 1,440 \text{ readings/day}$$
   $$\text{Total Daily Readings} = 4,500 \text{ sensors} \times 1,440 \text{ readings/day} = \mathbf{6,480,000 \text{ events/day}} \approx \mathbf{6.5 \times 10^6 \text{ events/day}}$$

2. **Average Telemetry Ingestion Rate (QPS)**:
   $$\text{QPS}_{\text{avg}} = \frac{6,480,000 \text{ events}}{86,400 \text{ seconds/day}} = \mathbf{75 \text{ events/second}}$$

3. **Peak Telemetry Ingestion Rate (Peak QPS)**:
   * Real-world IoT behavior exhibits **burstiness**: when cellular signal reconnects after transit through tunnels or cargo holds, hundreds of loggers dump queued batches simultaneously.
   * Applying a conservative peak-to-average ratio of **$4\times$ to $5\times$**:
   $$\text{QPS}_{\text{peak}} = 75 \times 5 = \mathbf{375 \text{ to } 400 \text{ events/second}}$$

---

### 3.2 Operational API Traffic (Transactions & Queries)

1. **Daily Operational Writes**:
   * New shipments: $1,000/\text{day}$
   * Leg status transitions (avg 5 legs $\times$ 2 events per leg): $10,000/\text{day}$
   * Warehouse slot reservations & check-ins: $4,000/\text{day}$
   * Incident creations & QA notes: $500/\text{day}$
   * **Total Operational Writes**: $\approx 15,500 \text{ write requests/day} \approx \mathbf{0.18 \text{ writes/sec}}$ (Negligible steady load; peaks under **15 writes/sec**).

2. **Daily Operational Reads (Dashboard, Queries, Tracking)**:
   * 1,000 active users making an average of 150 dashboard / query requests during an 8-hour shift.
   $$\text{Total Daily Reads} = 1,000 \text{ users} \times 150 \text{ requests} = 150,000 \text{ read requests/day}$$
   $$\text{Peak Read QPS (during 8-hour working window)} = \frac{150,000}{8 \times 3,600} \times 3 (\text{peak factor}) \approx \mathbf{15 \text{ to } 25 \text{ req/second}}$$

3. **External Carrier & Customs Polling / Webhooks**:
   * Approximately **10 to 20 webhook requests/sec**.

---

### 3.3 Storage Capacity Estimation

#### A. Relational Database Storage (PostgreSQL)

Let's break down the byte footprint of our core relational data models:

##### 1. Operational Business Entities (Shipment, Legs, Custody, Incidents):
* Shipment + Legs + Custody record + Audit overhead: $\approx 10 \text{ KB}$ per completed shipment.
* Daily volume: $1,000 \text{ shipments} \times 10 \text{ KB} = 10 \text{ MB/day}$.
* **Yearly Operational Data Growth**: $10 \text{ MB} \times 365 = \mathbf{3.65 \text{ GB/year}}$ (Very compact).

##### 2. High-Frequency Telemetry Ingestion:
* Telemetry Row Schema breakdown:
  * `id` (UUID): 16 bytes
  * `device_id` (VARCHAR(36)): 36 bytes
  * `shipment_id` (UUID): 16 bytes
  * `recorded_at` (TIMESTAMPTZ): 8 bytes
  * `temperature` (NUMERIC(5,2)): 8 bytes
  * `humidity` (NUMERIC(5,2)): 8 bytes
  * `latitude` / `longitude` (FLOAT8 $\times$ 2): 16 bytes
  * `battery_pct` (INT2): 2 bytes
  * PostgreSQL tuple header & padding: $\approx 28$ bytes
  * B-tree index overhead (`(shipment_id, recorded_at)`, `(device_id)`): $\approx 70$ bytes
  * **Estimated storage per telemetry row**: $\approx \mathbf{208 \text{ bytes}}$

* Daily Telemetry Volume:
  $$6,480,000 \text{ rows/day} \times 208 \text{ bytes} = 1,347,840,000 \text{ bytes} \approx \mathbf{1.35 \text{ GB/day}}$$

* Yearly Telemetry Volume:
  $$1.35 \text{ GB/day} \times 365 \text{ days} = \mathbf{492 \text{ GB/year}}$$
  * Accounting for Write-Ahead Logging (WAL) headroom, table bloat, and vacuuming margins ($+30\%$):
  * **Total Estimated Database Storage Growth**: $\approx \mathbf{650 \text{ GB to } 700 \text{ GB / year}}$.

---

#### B. Unstructured Document Storage (S3 / Blob Storage)

* Daily Document Uploads:
  $$1,000 \text{ shipments/day} \times 4 \text{ documents} = 4,000 \text{ documents/day}$$
* Daily Binary Storage:
  $$4,000 \text{ docs} \times 1.5 \text{ MB} = 6,000 \text{ MB/day} = \mathbf{6.0 \text{ GB/day}}$$
* Yearly Binary Storage Growth:
  $$6.0 \text{ GB/day} \times 365 = \mathbf{2,190 \text{ GB/year}} \approx \mathbf{2.2 \text{ TB / year}}$$

---

## 4. Architectural Deductions (How the Numbers Shape Design)

These calculations directly drive our architectural decisions:

| Finding from Math | Architectural Deduction | What We MUST Do | What We DO NOT Need |
| :--- | :--- | :--- | :--- |
| **Operational writes are tiny (< 20 req/s)** | Business transactions (booking, slot reservation, custody) will never saturate a modern PostgreSQL instance. | Focus deeply on **transaction correctness, ACID boundaries, and concurrency locking** rather than distributed database sharding. | No need for Cassandra, Spanner, or distributed multi-master writes for core business entities. |
| **Telemetry creates ~6.5M rows/day (~500 GB/yr)** | A single unpartitioned PostgreSQL table will degrade index performance and make vacuuming expensive as it reaches 50M+ rows. | Implement **table partitioning by month/week** (declarative partitioning or TimescaleDB) and an archival policy (move telemetry > 90 days to S3 Parquet). | No need for a separate big data cluster in V1. PostgreSQL handles 75–400 QPS comfortably when partitioned properly. |
| **Peak telemetry burst is 400 events/sec** | If a batch of IoT devices reconnects and posts 5,000 readings at once, direct synchronous processing through the main web thread could cause latency spikes. | In Phase 4/5, introduce **Kafka / message buffering** between ingestion endpoint and excursion evaluation. | Synchronous processing is acceptable in Phase 1 modular monolith testing, but must be decoupled before production launch. |
| **Document storage is ~2.2 TB/yr** | PostgreSQL is NOT designed for terabytes of binary payloads (database bloat, huge backup sizes, slow replication). | Store **only metadata and SHA-256 hashes in PostgreSQL**; store binaries in S3 / MinIO with pre-signed upload URLs. | Never store `bytea` document blobs directly inside PostgreSQL tables. |

---

## 5. What Happens at 10x Scale?

Let us evaluate the system if ColdChainOS grows to **500 enterprise tenants**:

| Dimension | Baseline (V1) | 10x Scale | Architectural Impact at 10x Scale |
| :--- | :--- | :--- | :--- |
| **Tenants** | 50 | **500** | 500 schemas in a single DB begins to stress PostgreSQL catalog cache (`pg_catalog`). Strategy: group tenants across 5–10 database instances (database-per-tenant cluster or tenant tiering). |
| **Active Sensors** | 4,500 | **45,000** | Peak telemetry ingestion reaches **3,500 to 4,000 events/sec**. |
| **Telemetry Volume** | 6.5M rows/day | **65M rows/day** (13 GB/day, **4.8 TB/year**) | Telemetry must be offloaded from PostgreSQL into an analytical time-series store (ClickHouse / Amazon Timestream / Apache Pinot) or S3 Iceberg data lake. Core operational DB retains only active shipment telemetry. |
| **API Ingestion Rate** | 75 QPS avg / 400 peak | **750 QPS avg / 4,000 peak** | Application pods scale horizontally from 3 to 15–20 instances via Kubernetes HPA. Kafka partition count increases from 6 to 24 partitions for parallel consumption. |
| **Document Storage** | 2.2 TB/year | **22 TB/year** | Transparent S3 Lifecycle transitions to S3 Glacier Flexible Retrieval after 90 days saves ~70% of storage cost. |

---

## 6. Interview Defense Summary (The "Why")

> **Interviewer**: *"Why didn't you start with Cassandra or MongoDB for IoT telemetry if you're ingesting millions of sensor readings?"*
>
> **Your Answer**:
> *"Our capacity calculations show that at baseline, 3,000 active shipments generate ~75 events/second steady and ~400 events/second peak. A single modern PostgreSQL 16 instance on standard cloud hardware handles 5,000 to 10,000 writes/second without tuning. 
> 
> By keeping telemetry in partitioned PostgreSQL tables initially, we maintain transactional correlation with shipments, simplify local development, and avoid the operational burden of managing a second distributed database cluster. We design our telemetry service with a clear repository interface so that when we reach 10x scale (4,000 events/sec), we can seamlessly reroute raw telemetry into ClickHouse or TimescaleDB without rewriting our domain logic."*
