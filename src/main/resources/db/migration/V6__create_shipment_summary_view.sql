-- ==============================================================================
-- ColdChainOS — V6 CQRS Read Model Schema (Shipment Summary View)
-- Materialized read-optimized projection table for sub-millisecond dashboard queries
-- ==============================================================================

CREATE TABLE IF NOT EXISTS shipment_summary_view (
    shipment_id UUID PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,
    tracking_number VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    thermal_category VARCHAR(32) NOT NULL,
    origin_code VARCHAR(16) NOT NULL,
    origin_city VARCHAR(64),
    destination_code VARCHAR(16) NOT NULL,
    destination_city VARCHAR(64),
    assigned_carrier_id VARCHAR(64),
    min_temperature NUMERIC(5,2) NOT NULL,
    max_temperature NUMERIC(5,2) NOT NULL,
    total_transit_legs INT NOT NULL DEFAULT 1,
    is_quarantined BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    last_event_type VARCHAR(128),
    last_event_timestamp TIMESTAMPTZ,
    projection_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_shipment_summary_status 
    ON shipment_summary_view (status, is_quarantined);

CREATE INDEX IF NOT EXISTS idx_shipment_summary_carrier 
    ON shipment_summary_view (assigned_carrier_id, status);

CREATE INDEX IF NOT EXISTS idx_shipment_summary_tracking 
    ON shipment_summary_view (tracking_number);
