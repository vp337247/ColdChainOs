-- ==============================================================================
-- ColdChainOS — V5 Idempotent Consumer & Deduplication Schema
-- Tracks processed event IDs per consumer to guarantee exactly-once business semantics
-- ==============================================================================

CREATE TABLE IF NOT EXISTS consumed_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_consumed_events_consumer_event UNIQUE (consumer_name, event_id)
);

CREATE INDEX IF NOT EXISTS idx_consumed_events_lookup 
    ON consumed_events (aggregate_id, event_type);
