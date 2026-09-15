-- ==============================================================================
-- ColdChainOS — V1 Core Schema
-- Tables: shipments, transit_legs, custody_records, warehouse_slots, slot_reservations
-- ==============================================================================

-- 1. Shipments Table
CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,
    tracking_number VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    pre_quarantine_status VARCHAR(32),
    min_temperature NUMERIC(5,2) NOT NULL,
    max_temperature NUMERIC(5,2) NOT NULL,
    max_excursion_seconds BIGINT NOT NULL,
    thermal_category VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    proof_of_delivery_signature TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_shipments_tenant_tracking UNIQUE (tenant_id, tracking_number)
);

CREATE INDEX idx_shipments_tenant_status ON shipments (tenant_id, status);
CREATE INDEX idx_shipments_created_at ON shipments (tenant_id, created_at DESC);

-- 2. Transit Legs Table
CREATE TABLE transit_legs (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    sequence_number INT NOT NULL,
    origin_code VARCHAR(16) NOT NULL,
    origin_name VARCHAR(128) NOT NULL,
    origin_city VARCHAR(64),
    origin_country VARCHAR(2) NOT NULL,
    destination_code VARCHAR(16) NOT NULL,
    destination_name VARCHAR(128) NOT NULL,
    destination_city VARCHAR(64),
    destination_country VARCHAR(2) NOT NULL,
    assigned_carrier_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    estimated_departure TIMESTAMPTZ,
    estimated_arrival TIMESTAMPTZ,
    CONSTRAINT uq_transit_legs_shipment_seq UNIQUE (shipment_id, sequence_number)
);

CREATE INDEX idx_transit_legs_shipment_id ON transit_legs (shipment_id);

-- 3. Custody Records Table (Immutable Chain of Custody)
CREATE TABLE custody_records (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    releasing_party VARCHAR(128) NOT NULL,
    receiving_party VARCHAR(128) NOT NULL,
    surface_temperature NUMERIC(5,2) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    digital_signature TEXT NOT NULL
);

CREATE INDEX idx_custody_records_shipment_id ON custody_records (shipment_id, recorded_at ASC);

-- 4. Warehouse Slots Table (For Concurrency Lab)
CREATE TABLE warehouse_slots (
    id UUID PRIMARY KEY,
    warehouse_id VARCHAR(64) NOT NULL,
    slot_code VARCHAR(32) NOT NULL,
    thermal_category VARCHAR(32) NOT NULL,
    max_capacity_kg NUMERIC(8,2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_warehouse_slots_wh_code UNIQUE (warehouse_id, slot_code)
);

CREATE INDEX idx_warehouse_slots_lookup ON warehouse_slots (warehouse_id, thermal_category);

-- 5. Slot Reservations Table
CREATE TABLE slot_reservations (
    id UUID PRIMARY KEY,
    slot_id UUID NOT NULL REFERENCES warehouse_slots(id) ON DELETE CASCADE,
    shipment_id UUID NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_slot_reservations_time_range CHECK (end_time > start_time)
);

CREATE INDEX idx_slot_reservations_slot_status ON slot_reservations (slot_id, status);
CREATE INDEX idx_slot_reservations_time_window ON slot_reservations (slot_id, start_time, end_time);
