-- ==============================================================================
-- ColdChainOS — V7 Distributed Saga State Table
-- Durable state and journal for cross-module shipment dispatch saga orchestration
-- ==============================================================================

CREATE TABLE IF NOT EXISTS dispatch_saga_state (
    saga_id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL,
    status VARCHAR(64) NOT NULL,
    current_step VARCHAR(64) NOT NULL,
    warehouse_slot_id UUID,
    carrier_booking_id VARCHAR(128),
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_dispatch_saga_shipment
    ON dispatch_saga_state (shipment_id);

CREATE INDEX IF NOT EXISTS idx_dispatch_saga_status
    ON dispatch_saga_state (status);
