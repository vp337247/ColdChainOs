-- ==============================================================================
-- ColdChainOS — V3 Transactional Outbox Schema
-- Stores domain events atomically with business state for reliable Kafka publishing
-- ==============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending_events 
    ON outbox_events (occurred_at ASC) 
    WHERE status = 'PENDING';
