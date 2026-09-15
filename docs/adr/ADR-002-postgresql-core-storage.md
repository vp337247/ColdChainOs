# ADR-002: PostgreSQL as Primary Datastore

## Status
**Accepted**

---

## Context
ColdChainOS manages legal chain-of-custody handoffs, warehouse slot reservations, regulatory compliance records (21 CFR Part 11), time-series sensor telemetry, and future vector embeddings for AI incident investigation. The system requires a highly reliable, durable, and versatile primary persistence engine.

---

## Problem
What database technology should serve as the primary datastore for ColdChainOS?

---

## Options Considered

### Option 1: Document NoSQL Database (e.g., MongoDB)
* **Pros**: Flexible JSON document schemas; rapid prototyping; built-in sharding.
* **Cons**:
  * Weak multi-document transaction semantics across collections compared to enterprise relational engines.
  * Inability to cleanly enforce relational foreign keys and business constraints (e.g., preventing overlapping time-range slot bookings).
  * Poor support for native schema-per-tenant isolation models without running multiple database clusters.

### Option 2: Distributed Columnar / Wide-Column Store (e.g., Apache Cassandra / ScyllaDB)
* **Pros**: Incredible linear write scalability for IoT telemetry; multi-datacenter masterless replication.
* **Cons**:
  * Cassandra is designed for high-write, eventually consistent workloads with no ACID joins or foreign keys.
  * Unusable for shipment lifecycle state machines, warehouse concurrency locks, and regulatory audit ledgers where immediate consistency and strict isolation are non-negotiable.

### Option 3: MySQL 8.x
* **Pros**: Widely understood, mature relational engine; reliable replication.
* **Cons**:
  * Inferior schema-per-tenant support and JSON query performance compared to PostgreSQL.
  * Lacks native vector search extension (`pgvector`), which would force introducing an additional vector database (Milvus/Pinecone) in Phase 11.

### Option 4: PostgreSQL 16+ (Selected)
* **Pros**:
  * **Rock-Solid ACID Compliance**: Uncompromising transactional guarantees for custody transfers, outbox tables, and warehouse reservations.
  * **Native Multi-Tenancy**: Native support for PostgreSQL schemas (`search_path = tenant_x`), enabling strong logical isolation within a single shared instance.
  * **Declarative Table Partitioning & JSONB**: Efficiently stores and queries time-series telemetry data and semi-structured device metadata.
  * **Single Technology Multi-Tool**: Supports relational data, transactional outbox queues, full-text search, and vector embeddings (`pgvector`) in one unified engine.
  * **Proven High Availability**: Industry-standard HA tooling (Patroni, PgBouncer, physical streaming replication, pg_rewind).
* **Cons**:
  * Connection model uses a process-per-connection architecture; requires a connection pooler (PgBouncer) under high client connection counts.
  * Requires scheduled autovacuum tuning to manage table bloat on high-update tables.

---

## Decision
We select **PostgreSQL 16+** as the unified primary operational datastore for ColdChainOS.

---

## Why We Selected It
1. **Compliance & Invariant Protection**: Physical custody handoffs and slot bookings demand serializable or pessimistic locking with strict relational integrity constraints. PostgreSQL is the industry benchmark for relational correctness.
2. **Schema-Per-Tenant Fit**: PostgreSQL allows creating isolated schemas (`tenant_pharma_a`, `tenant_food_b`) within a single database instance, providing clean data isolation at minimal infrastructure cost.
3. **Simplicity Across Phases**: PostgreSQL serves Phase 1 (domain tables), Phase 2 (concurrency locks), Phase 5 (transactional outbox table), and Phase 11 (vector embeddings via `pgvector`), dramatically reducing the operational surface area.

---

## Trade-offs & Failure Modes
* **Connection Saturation**: If hundreds of application threads open direct database connections, PostgreSQL memory usage spikes and performance degrades.
  * *Mitigation*: Enforce PgBouncer connection pooling in transaction mode in front of PostgreSQL.
* **Telemetry Table Bloat**: High-frequency telemetry inserts create large tables and indexes.
  * *Mitigation*: Use PostgreSQL declarative partitioning by month and implement an automated archival worker to offload aged telemetry (> 90 days) to S3 Parquet.

---

## What Happens at 10x Scale
At 10x scale (4,000 telemetry events/sec), raw sensor telemetry can be partitioned into TimescaleDB or offloaded to a specialized columnar analytical store (ClickHouse), while the core operational entities (shipments, warehouses, tenants, incidents) remain in PostgreSQL.
