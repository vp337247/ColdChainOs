-- ==============================================================================
-- ColdChainOS — V8 FDA 21 CFR Part 11 Cryptographic Compliance Audit Ledger
-- Immutable, append-only hash-chained ledger for pharmaceutical cold chain traceability
-- ==============================================================================

CREATE TABLE IF NOT EXISTS compliance_audit_ledger (
    ledger_id UUID PRIMARY KEY,
    sequence_number BIGINT NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    performed_by VARCHAR(128) NOT NULL,
    signature VARCHAR(256) NOT NULL,
    prev_hash VARCHAR(64) NOT NULL,
    hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_compliance_ledger_seq UNIQUE (sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_audit_ledger_aggregate
    ON compliance_audit_ledger (aggregate_id, aggregate_type);

CREATE INDEX IF NOT EXISTS idx_audit_ledger_seq
    ON compliance_audit_ledger (sequence_number ASC);
