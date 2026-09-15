# ColdChainOS — Functional Requirements (FR)

## Overview
This document specifies the core functional capabilities of ColdChainOS for initial release (V1) and planned operational increments. Each requirement is assigned a stable identifier (`FR-xxx`) for traceability across domain models, code implementations, and integration tests.

---

## 1. Tenant Management & Organization Context

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-001** | Tenant Onboarding | The platform must allow provisioning of new organizations (e.g., Pharma Importer, Food Distributor, 3PL Carrier, Warehouse Operator). | Each tenant receives a globally unique `tenant_id`, dedicated schema, and configuration defaults (timezone, regulatory profile). |
| **FR-002** | Tenant Configuration | Tenants must be able to define operating parameters: temperature unit (°C/°F), alert escalation emails, default compliance standards (FDA 21 CFR Part 11 vs EU GDP). | Configuration changes are versioned and logged for auditability. |
| **FR-003** | Tenant Suspension & Offboarding | Platform administrators can suspend or archive a tenant. | Suspended tenants cannot initiate new shipments or API operations; existing telemetry ingestion continues until active transit legs conclude. |

---

## 2. Identity & Access Control (RBAC)

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-004** | User Management | Organization admins can invite, update, and deactivate users within their tenant boundary. | Users belong strictly to one or more tenants with scoped permissions. |
| **FR-005** | Role-Based Permissions | Support granular roles: `TENANT_ADMIN`, `DISPATCHER`, `WAREHOUSE_OPERATOR`, `QA_OFFICER`, `AUDITOR`. | Endpoints strictly enforce role boundaries. No cross-tenant privilege escalation. |
| **FR-006** | Digital Signatures | In compliance with 21 CFR Part 11, high-impact actions (e.g., releasing quarantined shipment) require explicit re-authentication (username, password/MFA, and reason for action). | Signature records store hashed credentials verification timestamp, user identity, and statement of intent. |

---

## 3. Shipment Management & State Lifecycle

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-007** | Shipment Order Creation | A user creates a shipment specifying SKU, payload category, gross weight, target temp profile ($T_{min}$, $T_{max}$, max excursion duration), origin, and destination. | Generates a unique tracking code (e.g., `SHP-2026-XXXXX`). Initial state is `CREATED`. |
| **FR-008** | Multi-Leg Route Plan | Define an ordered sequence of transit legs (e.g., Origin Facility → Port of Exit → Port of Entry → Regional Warehouse → Final Hospital). | Legs have defined sequence numbers, expected departure/arrival windows, and expected transit modes. |
| **FR-009** | Shipment State Machine | Transition shipment through strict deterministic states: `CREATED` → `ASSIGNED` → `IN_TRANSIT` → `AT_PORT` → `WAREHOUSE` → `DELIVERED`. Exceptional terminal or interim states: `QUARANTINED`, `FAILED`, `CANCELLED`. | Illegal state transitions (e.g., `CREATED` → `DELIVERED`) are rejected with domain exceptions. State transitions are atomic. |
| **FR-010** | Chain of Custody Transfer | Record handoff of cargo between parties (Carrier to Warehouse, Carrier A to Carrier B). | Requires dual-party acknowledgment (or carrier signature + receiver scan) with physical surface temperature recorded at handoff. |
| **FR-011** | Shipment Cancellation | Allow cancellation of shipment prior to `IN_TRANSIT`. | Releases reserved carriers and warehouse slots atomically. |
| **FR-012** | Real-Time Status Query | Query shipment status, current leg, last known temperature, custody holder, and open incidents. | Read response p95 latency under 50ms. |

---

## 4. Carrier & Transport Management

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-013** | Carrier Directory | Maintain approved carrier profiles, reefer vehicle fleets, refrigeration capabilities, and certification status. | Inactive or uncertified carriers cannot be assigned to sensitive pharma tiers. |
| **FR-014** | Leg Carrier Assignment | Assign an internal fleet or 3PL carrier to a specific leg of a shipment. | Moves shipment from `CREATED` to `ASSIGNED` if all pre-conditions are met. |
| **FR-015** | Carrier Acknowledgment | Assigned carrier must electronically accept or reject leg dispatch within a configurable SLA window. | Unacknowledged assignments trigger escalation notifications. |

---

## 5. Warehouse Management & Slot Reservation

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-016** | Warehouse & Zone Topology | Define cold storage facilities, zones, and individual storage slots with explicit thermal classifications: Ultra-Cold (-80°C to -60°C), Frozen (-20°C), Cold (+2°C to +8°C), Controlled Ambient (+15°C to +25°C). | Slots have physical dimension limits, temperature ratings, and operational status (`AVAILABLE`, `RESERVED`, `OCCUPIED`, `MAINTENANCE`). |
| **FR-017** | Atomic Slot Reservation | Reserve a compatible slot for an incoming shipment arriving during window $[t_{start}, t_{end}]$. | **Strict Invariant**: One physical slot cannot be double-reserved for overlapping arrival windows under concurrent requests. |
| **FR-018** | Slot Check-In / Intake | When cargo arrives, operator scans shipment tag and commits intake to the reserved slot. | Slot status transitions from `RESERVED` to `OCCUPIED`. |
| **FR-019** | Slot Release / Checkout | When shipment departs warehouse, slot is marked `AVAILABLE` (or flagged for sanitization). | Slot becomes immediately available for future bookings. |

---

## 6. Telemetry Ingestion & Excursion Detection

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-020** | Telemetry Ingestion API | Ingest time-series readings from IoT data loggers (device ID, timestamp, temperature, humidity, battery level, GPS coordinates). | Idempotent by `(device_id, reading_timestamp)`. Accepts batch or single readings. |
| **FR-021** | Device-to-Shipment Mapping | Associate IoT logger device ID with active shipment for a bounded time window. | Readings received outside active shipment time window are stored in device telemetry log but decoupled from shipment profile. |
| **FR-022** | Real-Time Excursion Detection | Evaluate each reading against shipment's temperature threshold profile: Upper threshold ($T_{max}$), Lower threshold ($T_{min}$), and cumulative breach duration. | If temperature violates bounds, trigger immediate `TemperatureExcursionDetected` event. |
| **FR-023** | Mean Kinetic Temperature (MKT) | Compute MKT (Arrhenius-based thermal degradation metric) across the shipment journey on demand. | Enables QA to determine if brief high-temperature spikes thermally damaged the product. |

---

## 7. Incident Management & Quarantine Control

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-024** | Automated Incident Creation | System automatically creates an `INCIDENT` record upon confirmed excursion, physical shock, or customs delay exceeding threshold. | Severity assigned automatically (`SEV-1 Critical`, `SEV-2 High`, `SEV-3 Medium`). |
| **FR-025** | Automated Quarantine Lock | Severe temperature excursion automatically places shipment into `QUARANTINED` status. | Quarantined shipments are locked against dispatch, delivery sign-off, or warehouse slot checkout until formally cleared. |
| **FR-026** | QA Incident Triage & Resolution | QA Officer reviews incident, inspects temperature curves, attaches investigation notes, and logs resolution (`RELEASED`, `RETURNED_TO_ORIGIN`, `DESTROYED`). | Requires 21 CFR Part 11 compliant digital signature. |
| **FR-027** | Manual Incident Logging | Operators can manually log incidents (e.g., reefer compressor failure, broken security seal, customs paperwork error). | Captured with timestamp, user ID, photo evidence, and shipment correlation. |

---

## 8. Document & Compliance Management

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-028** | Document Attachment | Upload and associate documents (Bill of Lading, Certificate of Analysis [CoA], customs declarations, packing slips) with shipments. | File metadata stored in relational DB; binary content stored in blob storage (S3/MinIO). |
| **FR-029** | Document Validation Rules | Enforce that critical documents are present before shipment can transition to specific states (e.g., CoA required before `IN_TRANSIT`, Customs Release required before leaving `AT_PORT`). | State transition blocked if mandatory documents are missing. |
| **FR-030** | Tamper-Evident Hashing | Compute and persist SHA-256 hash of all compliance documents at upload time. | Verifiable during regulatory audit to prove documents were not altered post-upload. |

---

## 9. Notifications & Alert Escalation

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-031** | Event-Triggered Notifications | Dispatch notifications via Email, SMS, Webhook upon events: Excursion, Dispatch, Arrival, Quarantine. | Dispatched within 10 seconds of event detection. |
| **FR-032** | Alert Escalation Tiers | If a SEV-1 incident is unacknowledged within 15 minutes, automatically escalate to Director of Quality and Operations Lead. | Configurable escalation policies per tenant. |
| **FR-033** | Webhook Subscriptions | Allow external tenant ERPs to register webhooks for real-time shipment milestone updates. | Webhook dispatches include HMAC-SHA256 signature for verification and retry with exponential backoff. |

---

## 10. External Integrations (Carriers & Customs)

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-034** | Carrier Adapter Interface | Standardized internal contract (`CarrierProvider`) to communicate with 3PL carrier APIs (e.g., Carrier A, Carrier B) for booking, dispatch, and tracking. | Isolates core domain from vendor-specific payload formats and protocols. |
| **FR-035** | Customs Adapter Interface | Standardized contract (`CustomsProvider`) to submit clearance declarations and poll border clearance status. | Graceful fallback when customs portals experience high latency or 5xx downtime. |
| **FR-036** | Resilient Outbound Calls | All external API calls must execute through resilience wrappers (timeout, retry, circuit breaker, bulkhead). | A slow or failing 3PL carrier API cannot exhaust application thread pools or block user transactions. |

---

## 11. Audit Trail & Regulatory Compliance

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-037** | Immutable Audit Log | Every state change, custody transfer, slot reservation, and user override is recorded to an append-only audit log. | Audit entries cannot be updated or deleted by any user or application role. |
| **FR-038** | 21 CFR Part 11 Audit Trail Export | Generate tamper-evident audit report for any shipment including full sensor timeline, custody handoffs, and signatures. | Exportable as signed PDF/JSON with cryptographic checksum. |
| **FR-039** | Time Synchronization | System enforces UTC timestamps for all operational events across distributed timezones. | All records store ISO-8601 UTC timestamps with timezone offset where created. |

---

## 12. Knowledge Base & AI Incident Investigation (Phase 11-13)

| ID | Feature Name | Description | Acceptance Criteria / Business Invariant |
| :--- | :--- | :--- | :--- |
| **FR-040** | Operational SOP Ingestion | Ingest internal standard operating procedures, packaging specifications, and manufacturer product stability sheets. | Chunks and embeds documents into vector store with tenant metadata isolation. |
| **FR-041** | SOP Semantic Search | QA officers can search operating policies using natural language (e.g., "Allowable excursion limits for mRNA vaccines at +4°C"). | Returns grounded passages with direct document citations and confidence scores. |
| **FR-042** | AI Incident Investigation Agent | Autonomous agent assists QA by collecting telemetry, shipment parameters, and relevant SOPs to produce an incident recommendation. | **Strict Guardrail**: The AI Agent CANNOT execute irreversible state changes (e.g., quarantine release or disposal) without explicit human confirmation. |
