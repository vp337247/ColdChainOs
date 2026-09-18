package com.coldchainos.audit.application;

import com.coldchainos.audit.infrastructure.AuditLedgerEntryJpaEntity;
import com.coldchainos.audit.infrastructure.AuditLedgerJpaRepository;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * FDA 21 CFR Part 11 Verification Engine.
 * Mathematically validates the integrity of the cryptographic audit ledger.
 * Detects payload alterations, deleted entries, forged signatures, or broken hash links.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLedgerVerificationService {

    private final AuditLedgerJpaRepository ledgerRepository;

    /**
     * Traverses the tenant's entire audit ledger from genesis block to current head.
     * Recalculates and verifies SHA-256 hash chains across all blocks.
     */
    public TamperVerificationResult verifyLedgerIntegrity(TenantId tenantId) {
        return TenantContext.executeAs(tenantId, () -> {
            List<AuditLedgerEntryJpaEntity> blocks = ledgerRepository.findAllByOrderBySequenceNumberAsc();

            if (blocks.isEmpty()) {
                return TamperVerificationResult.success(0, AuditHashCalculator.GENESIS_HASH);
            }

            long expectedSeq = 0L;
            String expectedPrevHash = AuditHashCalculator.GENESIS_HASH;

            for (AuditLedgerEntryJpaEntity block : blocks) {
                // 1. Verify sequence monotonicity
                if (block.getSequenceNumber() != expectedSeq) {
                    log.error("[Audit Verification Failed] Broken sequence at block #{}. Expected {}, found {}",
                        block.getSequenceNumber(), expectedSeq, block.getSequenceNumber());
                    return TamperVerificationResult.corrupted(
                        blocks.size(),
                        block.getSequenceNumber(),
                        String.valueOf(expectedSeq),
                        String.valueOf(block.getSequenceNumber()),
                        "Sequence break detected. Missing or deleted blocks prior to sequence " + block.getSequenceNumber()
                    );
                }

                // 2. Verify predecessor hash link
                if (!Objects.equals(block.getPrevHash(), expectedPrevHash)) {
                    log.error("[Audit Verification Failed] Prev-hash mismatch at block #{}. Expected '{}', found '{}'",
                        block.getSequenceNumber(), expectedPrevHash, block.getPrevHash());
                    return TamperVerificationResult.corrupted(
                        blocks.size(),
                        block.getSequenceNumber(),
                        expectedPrevHash,
                        block.getPrevHash(),
                        "Predecessor hash mismatch. Chain has been severed at block " + block.getSequenceNumber()
                    );
                }

                // 3. Recalculate block digest from raw fields
                String recalculatedHash = AuditHashCalculator.calculateHash(
                    block.getSequenceNumber(),
                    block.getLedgerId(),
                    block.getTenantId(),
                    block.getAggregateId(),
                    block.getAggregateType(),
                    block.getEventType(),
                    block.getPayloadJson(),
                    block.getPerformedBy(),
                    block.getSignature(),
                    block.getPrevHash(),
                    block.getCreatedAt()
                );

                if (!Objects.equals(block.getHash(), recalculatedHash)) {
                    log.error("[Audit Verification Failed] Content tamper detected at block #{}. Computed '{}', found in DB '{}'",
                        block.getSequenceNumber(), recalculatedHash, block.getHash());
                    return TamperVerificationResult.corrupted(
                        blocks.size(),
                        block.getSequenceNumber(),
                        recalculatedHash,
                        block.getHash(),
                        "Cryptographic hash mismatch. Block contents (payload, metadata, or signature) have been tampered with!"
                    );
                }

                // Advance to next block
                expectedSeq++;
                expectedPrevHash = block.getHash();
            }

            String headHash = blocks.get(blocks.size() - 1).getHash();
            log.info("[Audit Verification Passed] Successfully verified {} audit ledger block(s) for tenant '{}'. Head hash: {}",
                blocks.size(), tenantId.value(), headHash);

            return TamperVerificationResult.success(blocks.size(), headHash);
        });
    }
}
