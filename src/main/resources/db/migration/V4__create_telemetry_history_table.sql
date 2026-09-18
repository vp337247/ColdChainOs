-- ==============================================================================
-- ColdChainOS — V4 Telemetry Time-Series History Schema
-- Stores historical IoT sensor readings for temperature tracking & compliance audits
-- ==============================================================================

CREATE TABLE IF NOT EXISTS shipment_telemetry_history (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    sensor_id VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    temperature_celsius NUMERIC(5,2) NOT NULL,
    humidity_percentage NUMERIC(5,2),
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    battery_level NUMERIC(5,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_telemetry_shipment_time 
    ON shipment_telemetry_history (shipment_id, recorded_at DESC);
