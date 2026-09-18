package com.coldchainos.audit.application;

import com.coldchainos.audit.infrastructure.AuditLedgerEntryJpaEntity;
import com.coldchainos.audit.infrastructure.AuditLedgerJpaRepository;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating append-only cryptographic logging for FDA 21 CFR Part 11 records.
 * Ensures every event is appended with a monotonic sequence and SHA-256 predecessor hash link.
 */
@Slf4j
@Service
public class AuditLedgerService {

    private final AuditLedgerJpaRepository ledgerRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public AuditLedgerService(
        AuditLedgerJpaRepository ledgerRepository,
        org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.ledgerRepository = ledgerRepository;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Appends an immutable event record to the tenant's cryptographic ledger.
     */
    public AuditLedgerEntryJpaEntity appendEvent(
        TenantId tenantId,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payloadJson,
        String performedBy,
        String signature
    ) {
        return TenantContext.executeAs(tenantId, () ->
            transactionTemplate.execute(status -> {
                Optional<AuditLedgerEntryJpaEntity> latestOpt = ledgerRepository.findTopByOrderBySequenceNumberDesc();

                long sequenceNumber;
                String prevHash;

                if (latestOpt.isEmpty()) {
                    sequenceNumber = 0L;
                    prevHash = AuditHashCalculator.GENESIS_HASH;
                } else {
                    AuditLedgerEntryJpaEntity latest = latestOpt.get();
                    sequenceNumber = latest.getSequenceNumber() + 1L;
                    prevHash = latest.getHash();
                }

                UUID ledgerId = UUID.randomUUID();
                Instant now = Instant.now();

                String hash = AuditHashCalculator.calculateHash(
                    sequenceNumber,
                    ledgerId,
                    tenantId.value(),
                    aggregateId,
                    aggregateType,
                    eventType,
                    payloadJson,
                    performedBy,
                    signature,
                    prevHash,
                    now
                );

                AuditLedgerEntryJpaEntity entry = new AuditLedgerEntryJpaEntity(
                    ledgerId,
                    sequenceNumber,
                    tenantId.value(),
                    aggregateId,
                    aggregateType,
                    eventType,
                    payloadJson,
                    performedBy,
                    signature,
                    prevHash,
                    hash,
                    now
                );

                AuditLedgerEntryJpaEntity saved = ledgerRepository.saveAndFlush(entry);
                log.info("[Audit Ledger] Appended block #{} for aggregate '{}' [eventType={}, hash={}]",
                    sequenceNumber, aggregateId, eventType, hash);

                return saved;
            })
        );
    }

    /**
     * Returns the chronological audit trail for a specific aggregate root.
     */
    public List<AuditLedgerEntryJpaEntity> getAuditTrail(TenantId tenantId, UUID aggregateId) {
        return TenantContext.executeAs(tenantId, () ->
            ledgerRepository.findByAggregateIdOrderBySequenceNumberAsc(aggregateId)
        );
    }
}
