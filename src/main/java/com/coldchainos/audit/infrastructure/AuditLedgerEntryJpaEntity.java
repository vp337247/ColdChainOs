package com.coldchainos.audit.infrastructure;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity mapping the immutable compliance_audit_ledger table.
 */
@Entity
@Table(name = "compliance_audit_ledger")
@Getter
@Setter
@NoArgsConstructor
public class AuditLedgerEntryJpaEntity {

    @Id
    @Column(name = "ledger_id", nullable = false)
    private UUID ledgerId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "performed_by", nullable = false, length = 128)
    private String performedBy;

    @Column(name = "signature", nullable = false, length = 256)
    private String signature;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AuditLedgerEntryJpaEntity(
        UUID ledgerId,
        long sequenceNumber,
        String tenantId,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payloadJson,
        String performedBy,
        String signature,
        String prevHash,
        String hash,
        Instant createdAt
    ) {
        this.ledgerId = ledgerId;
        this.sequenceNumber = sequenceNumber;
        this.tenantId = tenantId;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.performedBy = performedBy;
        this.signature = signature;
        this.prevHash = prevHash;
        this.hash = hash;
        this.createdAt = createdAt;
    }
}
