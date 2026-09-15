# ADR-003: Multi-Tenancy Strategy — Schema-per-Tenant

## Status
**Accepted**

---

## Context
ColdChainOS serves distinct organizations: pharmaceutical manufacturers, hospital networks, food distributors, and 3PL carriers. Many of these tenants are direct commercial competitors. Furthermore, pharmaceutical regulations (FDA 21 CFR Part 11, EU GDP) mandate strict data isolation and auditability. Accidental cross-tenant data leakage is a catastrophic compliance and legal failure.

---

## Problem
What multi-tenancy architecture should ColdChainOS implement to balance data isolation, operational complexity, cloud cost, and developer velocity?

---

## Architectural Options Comparison

| Dimension | Option 1: Shared Schema (Discriminator Column) | Option 2: Schema-per-Tenant (Selected for V1) | Option 3: Database-per-Tenant |
| :--- | :--- | :--- | :--- |
| **Isolation Level** | **Weak / Logical**: Tables contain a `tenant_id` column. Isolation relies entirely on application queries or PostgreSQL Row-Level Security (RLS). | **Strong Logical**: Each tenant has a dedicated PostgreSQL schema (`tenant_a`, `tenant_b`). Hard namespace separation. | **Physical**: Each tenant has a completely separate PostgreSQL database instance or cluster. |
| **Data Leakage Risk** | **High Risk**: A developer forgetting `WHERE tenant_id = :id` in a native SQL query exposes competitor data. | **Very Low Risk**: Queries default to the tenant's schema search path (`SET search_path = tenant_x`). Cross-schema queries fail automatically. | **Zero Risk**: Physical database separation eliminates shared query paths. |
| **Infrastructure Cost** | **Lowest**: Single database instance, shared connection pools and buffers. | **Low**: Single database instance, shared memory buffers, partitioned namespaces. | **High**: Paying for 50+ database instances; significant idle memory and compute waste. |
| **Schema Migrations** | **Trivial**: Run migration script once against the single shared schema. | **Moderate**: Migration runner (Flyway) loops through all tenant schemas and applies versioned scripts. | **Complex**: Coordinated rollout across dozens/hundreds of independent database endpoints. |
| **Connection Pooling** | **Simple**: Standard connection pool shared across all requests. | **Managed**: Dynamic tenant resolution sets `search_path` per connection checkout or tenant datasource routing. | **Complex**: Separate connection pool per database; quickly exhausts OS file descriptors. |
| **Tenant Lifecycle** | **Hard**: Deleting or exporting a tenant requires cascaded row deletes across all tables. | **Easy**: Archiving a tenant is a clean `pg_dump -n tenant_x` and `DROP SCHEMA tenant_x CASCADE`. | **Easy**: Drop or archive the entire database volume. |
| **Optimal Tenant Scale**| 10,000+ small self-serve tenants. | **20 to 200 medium-to-enterprise tenants**. | 5 to 10 massive Fortune 500 enterprise tenants. |

---

## Decision
We select **Option 2: Schema-per-Tenant** in PostgreSQL for ColdChainOS V1.

Each tenant organization is assigned a dedicated PostgreSQL schema named `tenant_<tenant_id>` (e.g., `tenant_pharma_a`). A shared `public` schema stores global platform metadata, tenant registration catalogs, and system-wide configuration.

---

## Why We Selected It
1. **Zero-Accidental Leakage Guarantee**: In a highly regulated pharma supply chain, a single query bug leaking batch numbers or temperature excursions to a competitor can result in regulatory shutdowns and lawsuits. Schema-level isolation prevents accidental queries across tenant boundaries by design.
2. **Cost-Efficiency for Enterprise Target**: Our capacity model targets 20 to 50 enterprise tenants. Running 50 separate RDS instances would cost thousands of dollars per month in idle overhead. A single PostgreSQL instance hosting 50 schemas uses resources efficiently while providing clean boundaries.
3. **Tenant Audit & Compliance Export**: Regulatory bodies frequently demand a full database dump of a specific organization's audit trail. With schema-per-tenant, running `pg_dump --schema=tenant_pharma_a` produces a clean, self-contained export in seconds.

---

## Technical Implementation Details
* **Tenant Context Interceptor**: Every incoming HTTP request parses the authenticated user's `tenant_id` from the verified JWT token and binds it to a `ThreadLocal` / `ScopedValue` context.
* **Connection Routing**: When borrowing a database connection from the pool, the application sets the PostgreSQL search path:
  ```sql
  SET search_path TO tenant_pharma_a, public;
  ```
  Upon returning the connection to the pool, the search path is cleanly reset (`RESET search_path;`).
* **Automated Migration Runner**: On application startup or deployment, a custom migration coordinator iterates over all registered schemas in the tenant catalog and executes versioned Flyway migrations against each schema sequentially.

---

## Consequences & Trade-offs
* **Catalog Bloat at Large Scale**: PostgreSQL maintains metadata tables (`pg_class`, `pg_attribute`) for every table in every schema. While 50 schemas with 30 tables each (1,500 total tables) runs effortlessly, exceeding 500+ schemas can slow down PostgreSQL catalog queries.
* **Evolution Path (10x Scale)**: When ColdChainOS scales beyond 200 tenants, we will adopt a **Hybrid Model**: standard tenants run on schema-per-tenant clusters, while Tier-1 mega-tenants (e.g., global pharma giants with dedicated compliance mandates) can be provisioned on dedicated database-per-tenant instances using the exact same code and schema definitions.
