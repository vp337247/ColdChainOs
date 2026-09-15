# ColdChainOS — Non-Functional Requirements (NFR)

## Overview
Non-functional requirements (NFRs) define the operational qualities, architectural constraints, and service level objectives (SLOs) required of ColdChainOS. Each requirement is assigned an identifier (`NFR-xxx`) with clear measurable metrics.

---

## 1. Availability, SLA, and Error Budget

| ID | Attribute | Target Metric | Architectural Meaning & Justification |
| :--- | :--- | :--- | :--- |
| **NFR-001** | Platform Availability | **99.9% ("Three Nines")** across core APIs. | Allows **43.8 minutes** of total downtime per month. Telemetry ingestion must target **99.95%** (21.9 min/month) because IoT loggers in transit have limited local buffer memory. |
| **NFR-002** | Recovery Point Objective (RPO) | **RPO ≤ 0** for committed financial/custody transactions (zero data loss).<br>**RPO ≤ 5 seconds** for high-frequency telemetry streams. | Relational DB commits use synchronous replication or Write-Ahead Logging (WAL) archiving to prevent loss of legal custody handoffs. |
| **NFR-003** | Recovery Time Objective (RTO) | **RTO ≤ 15 minutes** for unplanned database failover.<br>**RTO ≤ 2 minutes** for stateless application pod failures. | Automated health checks, liveness/readiness probes, and Patroni/PgBouncer automatic leader failover. |
| **NFR-004** | Error Budget Policy | If monthly error budget (< 0.1% failed requests) drops below 20%, freeze non-critical feature deployments; prioritize reliability. | Standard SRE operational discipline. |

---

## 2. Latency & Performance Targets

| ID | Operation Category | Metric Target | Conditions & Context |
| :--- | :--- | :--- | :--- |
| **NFR-005** | Operational Reads (Shipment status, slot lookup) | **p50 < 20 ms**<br>**p95 < 50 ms**<br>**p99 < 150 ms** | Measured at the API gateway boundary under peak design load (excluding client network latency). |
| **NFR-006** | Operational Writes (Create shipment, reserve slot) | **p50 < 50 ms**<br>**p95 < 120 ms**<br>**p99 < 250 ms** | Includes ACID database transaction commit and synchronous outbox table append. |
| **NFR-007** | Telemetry Ingestion Endpoint | **p50 < 15 ms**<br>**p95 < 35 ms**<br>**p99 < 80 ms** | Fast ingest acknowledgment. Device payloads are validated, idempotency checked, and staged for asynchronous excursion processing. |
| **NFR-008** | Excursion Detection Pipeline | **End-to-end latency < 3.0 seconds** | Time from HTTP ingestion of an excursion reading to generation of the incident and trigger of alert notification. |
| **NFR-009** | Full-Text / RAG Document Retrieval (Phase 11) | **p95 < 800 ms** | Vector search query + dense re-ranking over SOP database. |

---

## 3. Throughput & Scalability

| ID | Metric | Baseline Design Target (V1) | 10x Scale Target |
| :--- | :--- | :--- | :--- |
| **NFR-010** | Core API Request Rate | **100 requests/sec** steady state<br>**300 requests/sec** peak | **1,000 req/sec** steady<br>**3,000 req/sec** peak |
| **NFR-011** | Telemetry Ingestion Rate | **500 events/sec** steady state<br>**1,500 events/sec** peak | **5,000 events/sec** steady<br>**15,000 events/sec** peak |
| **NFR-012** | Database Connections | Connection pool capped at **50–100 active connections** per instance via PgBouncer. | Prevents thread-per-connection PostgreSQL backend exhaustion. |
| **NFR-013** | Horizontal Scalability | Stateless application tiers must scale out horizontally with zero downtime under Kubernetes HPA (CPU > 70% or request queue depth). | Modularity ensures no sticky sessions or in-memory state in web containers. |

---

## 4. Multi-Tenancy & Data Isolation

| ID | Requirement | Architectural Enforcement |
| :--- | :--- | :--- |
| **NFR-014** | Logical Data Isolation | ColdChainOS will use **schema-per-tenant** in PostgreSQL for V1. Every tenant's operational data lives in its own dedicated schema (`tenant_pharma_a`, `tenant_food_b`). | Guarantees hard logical boundaries. SQL queries cannot inadvertently query another tenant's tables without intentional cross-schema syntax. |
| **NFR-015** | Strict Tenant Context Propagation | Every incoming request must resolve a verified `tenant_id` from the authenticated JWT token or API key. The application framework automatically binds the tenant context to the database connection search path. | Eliminates manual `WHERE tenant_id = ?` query bugs in business logic. |
| **NFR-016** | Noisy Neighbor Protection | Rate limiting applied per tenant (e.g., 50 req/sec for standard tier; 200 req/sec for enterprise tier). | An aggressive telemetry loop or batch export from Tenant A cannot starve Tenant B's interactive UI users. |

---

## 5. Security & Regulatory Compliance

| ID | Requirement | Implementation Specification |
| :--- | :--- | :--- |
| **NFR-017** | Data Encryption at Rest | AES-256 encryption applied to all relational databases, replica volumes, document stores (S3), and Kafka logs. | Managed via KMS with per-tenant key rotation options. |
| **NFR-018** | Data Encryption in Transit | TLS 1.3 mandatory for all ingress and inter-service communication. Older TLS versions (< 1.2) explicitly rejected. | Enforced at Load Balancer and internal service mesh/proxies. |
| **NFR-019** | Authentication & RBAC | Stateless OAuth2 / OpenID Connect (OIDC) JWT tokens with short expiry (15 min) and refresh token rotation. | Tokens include verified claims: `sub`, `tenant_id`, `roles`, `permissions`. |
| **NFR-020** | Regulatory 21 CFR Part 11 Compliance | System enforces: (1) Unique user accounts (no shared logins); (2) Automatic session timeout after 15 min inactivity; (3) Tamper-evident audit logs; (4) Cryptographic digital signatures on critical actions. | Complies with FDA electronic records standards. |
| **NFR-021** | Secret Management | Zero hardcoded secrets, credentials, or API keys in source control or configuration files. | Injected at runtime via AWS Secrets Manager / HashiCorp Vault. |

---

## 6. Fault Tolerance & Resilience

| ID | Failure Mode | Mitigation Strategy |
| :--- | :--- | :--- |
| **NFR-022** | Primary Database Crash | Automated failover to standby replica via Patroni/Consul within **< 30 seconds**. Application pools reconnect automatically with exponential backoff. |
| **NFR-023** | 3PL Carrier / External API Failure | Outbound calls wrapped with **Resilience4j** (timeout = 3s, retry with jitter = 3 attempts, circuit breaker trips at 50% failure rate). Fallback to async retry queue. |
| **NFR-024** | Message Broker Outage (Kafka down) | Application writes events to local database **Transactional Outbox** table in the same ACID transaction as the business entity. Zero message loss if Kafka is temporarily offline. |
| **NFR-025** | Network Partitions & Split-Brain | Odd-number cluster quorums (3 Consul nodes, 3 Kafka controllers/Zookeepers). Strict fencing mechanisms prevent dual primary databases. |

---

## 7. Observability & SRE

| ID | Pillar | Standard & Enforcement |
| :--- | :--- | :--- |
| **NFR-026** | Structured Logging | JSON-formatted structured logs to stdout. Must contain: `timestamp`, `trace_id`, `span_id`, `tenant_id`, `user_id`, `level`, `logger`, `message`, `exception_stack`. Sensitive PII/PHI masked. |
| **NFR-027** | Metrics & SLI Monitoring | Prometheus format endpoints exposing JVM metrics, DB pool saturation (active, idle, wait time), HTTP request durations (histogram), and domain metrics (`excursions_total`, `slots_reserved_total`). |
| **NFR-028** | Distributed Tracing | OpenTelemetry (OTel) context propagation across HTTP headers and Kafka record headers (`traceparent`). Complete trace visualization across service hops. |
| **NFR-029** | Alerting & Incident Response | Automated alerts routed via Alertmanager / PagerDuty for: error budget burn rate > 14x (critical), DB replication lag > 10s, consumer group lag > 5,000 msgs. |
