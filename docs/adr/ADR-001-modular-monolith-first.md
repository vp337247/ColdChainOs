# ADR-001: Adopt a Modular Monolith Architecture First

## Status
**Accepted**

---

## Context
ColdChainOS is an enterprise multi-tenant platform for temperature-sensitive cold chain logistics. The system must support diverse capabilities: shipment tracking, warehouse slot reservations, IoT telemetry ingestion, incident triage, and document verification. 

At project inception, the domain boundaries, data models, and operational workflows are evolving. The team must choose an initial software architecture pattern that balances rapid development velocity, high architectural clarity, robust transaction management, and future scalability.

---

## Problem
Should ColdChainOS be built from day one as a distributed microservices architecture (a dozen independently deployed services communicating over HTTP/gRPC/Kafka), or as a single, well-structured modular monolith?

---

## Options Considered

### Option 1: Distributed Microservices Architecture from Day One
* **Description**: Decompose each bounded context (`shipment-service`, `warehouse-service`, `telemetry-service`, `incident-service`, `iam-service`) into separate Git repositories, Docker containers, and CI/CD pipelines communicating over network RPC and message brokers.
* **Pros**: Independent deployability of individual services; polyglot persistence option; isolated blast radius of runtime crashes.
* **Cons**:
  * **Distributed Transaction Tax**: Enforcing invariants across domains (e.g., reserving a warehouse slot during shipment booking) requires complex distributed sagas, 2-phase commits, or eventual consistency edge cases.
  * **Network Latency & Failure Modes**: Every in-process call becomes an RPC vulnerable to network timeouts, connection pool exhaustion, partial failures, and cascading outages.
  * **Operational Burden**: Requires distributed tracing, service discovery, API gateways, centralized logging, and Kubernetes infrastructure before a single business feature is even proven.
  * **Refactoring Penalty**: When domain boundaries change (as they frequently do in early-stage software), refactoring across service boundaries requires coordinated cross-repo releases and breaking API migrations.

### Option 2: Unstructured ("Big Ball of Mud") Monolith
* **Description**: Single application with shared models, unstructured package layouts, and open database queries across all tables without boundaries.
* **Pros**: Extremely fast to hack together initial prototypes.
* **Cons**: Degrades into spaghetti code within weeks; impossible to extract services later; impossible to reason about transactional boundaries or enforce tenant isolation.

### Option 3: Modular Monolith (Selected)
* **Description**: Single deployable runtime process (Spring Boot application), but organized internally into strictly decoupled, self-contained modules (`shipment`, `warehouse`, `telemetry`, `incident`, `tenant`). Modules interact exclusively through well-defined public Java interfaces and domain events. Cross-module database table joins are prohibited in code.
* **Pros**:
  * In-process calls have **zero network latency** (< 1 microsecond).
  * Local ACID database transactions can guarantee atomicity across module boundaries when strictly required.
  * Fast developer feedback loop: single project to build, run, and debug in an IDE.
  * Preserves clean architectural boundaries: if a specific module (e.g., high-throughput `telemetry-service`) later requires independent scaling, it can be extracted into an independent microservice with minimal friction because its interface and data ownership are already decoupled.
* **Cons**:
  * Requires strict architectural discipline to prevent developers from bypassing module interfaces and directly accessing other modules' internal classes.
  * Entire monolith deploys as a single binary; a memory leak in one module affects the runtime process.

---

## Decision
We will build ColdChainOS as a **Modular Monolith** using Java 21 and Spring Boot 3.x.

We will enforce modular boundaries using package visibility conventions, domain interfaces, and ArchUnit automated tests to prevent unauthorized cross-module coupling.

---

## Why We Selected It
1. **Domain Discovery Priority**: Cold chain business invariants (e.g., temperature threshold grace periods, atomic slot allocation) require deep domain clarity. A modular monolith allows us to iterate on domain models without the friction of distributed network boundaries.
2. **Transactional Integrity**: Our capacity estimation shows operational writes are modest (< 20 QPS). Relational ACID transactions inside PostgreSQL eliminate the need for distributed Sagas during V1.
3. **Operational Simplicity**: A single container can be tested hermetically with Docker Compose, simplifying CI/CD and developer onboarding.

---

## Trade-offs & Negative Consequences
* **Deployment Coupling**: Deploying a bug fix in the notification module requires restarting the entire monolith application instance.
* **Scaling Monolith**: The entire container must be scaled horizontally, rather than scaling only CPU-heavy or I/O-heavy modules. (Given modern container resources, this cost is trivial compared to developer overhead).

---

## How It Evolves at 10x Scale
If telemetry volume reaches tens of thousands of readings per second in Phase 14, the `telemetry` module—having clean repository and service interfaces—can be extracted into a standalone container backed by a dedicated time-series datastore without modifying the core `shipment` or `warehouse` modules.
