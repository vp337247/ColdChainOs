# ColdChainOS — Assumptions and Constraints

## Overview
Architectural decisions are meaningful only within the context of their assumptions and constraints. This document records the foundational assumptions regarding business volume, operational environment, technology choices, and regulatory boundaries governing ColdChainOS.

---

## 1. Business & Operational Assumptions

| # | Domain | Assumption | Architectural Impact |
| :--- | :--- | :--- | :--- |
| **A-001** | Tenant Scale | V1 targets **20 to 50 active tenants** (pharma importers, food distributors, 3PL carriers, cold warehouse operators). | Schema-per-tenant in PostgreSQL is optimal at this count (tens of schemas). Avoids overhead of managing 50 separate database instances. |
| **A-002** | Shipment Duration | Active transit time for a shipment ranges from **12 hours** (local reefer delivery) to **14 days** (intercontinental air/sea freight). | Operational tables hold active shipments; completed shipments (> 90 days) can be archived or partitioned. |
| **A-003** | Sensor Telemetry Frequency | An active shipment has 1 to 2 attached IoT logger devices reporting environmental metrics every **60 seconds** while in motion, and every **15 minutes** while stationary in warehouse. | Allows precise capacity estimation for telemetry throughput and time-series table growth. |
| **A-004** | Document Profile | A typical shipment requires **3 to 7 compliance documents** (Bill of Lading, Certificate of Analysis, Packing List, Customs Release, Sensor Calibrations). Average document size is **1.5 MB** (PDF/TIFF). | Relational database must NOT store file blobs. Database stores metadata, hashes, and URLs; object storage (S3 / MinIO) stores binaries. |
| **A-005** | Handoff Concurrency | A warehouse facility has multiple dispatch docks and handling bays. Up to **10–20 dock workers and automated booking systems** may attempt to allocate slots simultaneously during shift peaks. | High probability of race conditions on slot reservation; requires robust concurrency control (pessimistic locking / serializable isolation). |
| **A-006** | Connectivity Reality | IoT devices in transit may travel through tunnels, deep sea, or flight mode with zero connectivity for hours. Upon regaining signal, they dump a **burst of 200–500 buffered readings**. | Telemetry ingestion must handle batch payloads, out-of-order timestamps, and deduplication idempotently. |

---

## 2. Technical & Architectural Constraints

| # | Category | Constraint | Rationale / Mitigation |
| :--- | :--- | :--- | :--- |
| **C-001** | Architecture Style | **Modular Monolith first.** No distributed microservices in early phases. | Avoids distributed transaction overhead, network latency between domain boundaries, and premature operational complexity while domain boundaries stabilize. |
| **C-002** | Core Technology Stack | **Java 21 LTS + Spring Boot 3.x**. | Modern enterprise platform: virtual threads (Project Loom) for high-concurrency I/O, mature ecosystem, strong typing for complex domain models, rich testing harness. |
| **C-003** | Primary Storage | **PostgreSQL 16+**. | Relational ACID guarantees are mandatory for custody tracking, inventory reservation, and compliance audit logs. PostgreSQL also supports schema isolation and `pgvector` for AI embeddings. |
| **C-004** | Stateless Application Tier | Application server instances must maintain **zero sticky in-memory session state**. | Any instance can process any request. Allows dynamic auto-scaling, fast pod restarts, and zero-downtime rolling deployments. |
| **C-005** | Database Schema Evolution | All database schema changes must be versioned, immutable, and applied automatically via **Flyway** or **Liquibase**. | No manual DDL executions in production. Zero configuration drift between environments. |
| **C-006** | Clock Synchronization | All timestamps must be stored and processed in **UTC** (ISO-8601 format). System hosts must synchronize via NTP. | Critical for chronologically ordering multi-leg handoffs and correlating sensor readings across global time zones. |

---

## 3. Regulatory & Compliance Constraints

| # | Regulation | Constraint | Implementation Strategy |
| :--- | :--- | :--- | :--- |
| **RC-001** | FDA 21 CFR Part 11 | Electronic signatures and audit trails must be computer-generated, time-stamped, tamper-evident, and traceable to an individual user. | System provides immutable audit logs and explicit digital re-authentication on quarantine override or release. |
| **RC-002** | Data Retention & GDP | Complete shipment audit records and temperature logs must be preserved and retrievable for at least **5 years** (pharma standard) or shelf-life + 1 year. | Hot/warm operational storage in PostgreSQL for active/recent shipments; cold storage archive in compressed parquet/S3 for compliance historical lookups. |
| **RC-003** | Data Residency & Privacy | Tenant data must not mix. Personal identifiable information (PII) of drivers and warehouse staff must comply with GDPR/CCPA. | Schema-level isolation; sensitive driver contact info encrypted and restricted by RBAC. |

---

## 4. Cost & Operational Constraints

| # | Resource | Constraint | Architectural Implication |
| :--- | :--- | :--- | :--- |
| **OC-001** | Cloud Infrastructure Footprint | Development and local testing must be fully runnable inside a single developer machine via Docker Compose (PostgreSQL, Redis, Kafka, LocalStack/MinIO). | Enables rapid test cycles, hermetic CI/CD pipelines, and zero dependence on expensive external cloud environments during local development. |
| **OC-002** | High Availability Cost | Production requires Multi-AZ database failover, but does NOT require active-active multi-region database replication in Phase 1–8. | Active-passive failover within a single AWS region (Multi-AZ) delivers 99.9% availability at a fraction of cross-region distributed consensus complexity and cost. |
