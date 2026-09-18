package com.coldchainos.audit.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLedgerJpaRepository extends JpaRepository<AuditLedgerEntryJpaEntity, UUID> {

    /**
     * Retrieves the latest block in the audit ledger for hash chain linkage.
     */
    Optional<AuditLedgerEntryJpaEntity> findTopByOrderBySequenceNumberDesc();

    /**
     * Retrieves all ledger entries sequentially from genesis to head for integrity verification.
     */
    List<AuditLedgerEntryJpaEntity> findAllByOrderBySequenceNumberAsc();

    /**
     * Retrieves the complete immutable event history for a specific aggregate root.
     */
    List<AuditLedgerEntryJpaEntity> findByAggregateIdOrderBySequenceNumberAsc(UUID aggregateId);
}
