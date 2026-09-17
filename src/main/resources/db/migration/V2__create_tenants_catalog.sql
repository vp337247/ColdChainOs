-- ==============================================================================
-- ColdChainOS — V2 Multi-Tenancy Catalog
-- Stores tenant organizations and their provisioned PostgreSQL schema namespaces
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.tenants (
    id VARCHAR(32) PRIMARY KEY,
    organization_name VARCHAR(128) NOT NULL,
    schema_name VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tenants_status ON public.tenants (status);
