package com.coldchainos.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Computes deterministic cryptographic SHA-256 hashes for FDA 21 CFR Part 11 audit records.
 * Binds payload, metadata, operator signature, timestamp, and predecessor hash.
 */
public final class AuditHashCalculator {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private AuditHashCalculator() {
        // Utility class
    }

    /**
     * Computes the cryptographic SHA-256 block digest for a ledger entry.
     */
    public static String calculateHash(
        long sequenceNumber,
        UUID ledgerId,
        String tenantId,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        String payloadJson,
        String performedBy,
        String signature,
        String prevHash,
        Instant createdAt
    ) {
        String dataToHash = String.join(":",
            String.valueOf(sequenceNumber),
            ledgerId.toString(),
            tenantId,
            aggregateId.toString(),
            aggregateType,
            eventType,
            payloadJson,
            performedBy,
            signature,
            prevHash,
            String.valueOf(createdAt.toEpochMilli())
        );

        return sha256Hex(dataToHash);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 cryptographic algorithm not available in JVM", e);
        }
    }
}
