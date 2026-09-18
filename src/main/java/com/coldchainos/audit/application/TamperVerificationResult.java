package com.coldchainos.audit.application;

/**
 * Outcome of an FDA 21 CFR Part 11 cryptographic audit ledger integrity check.
 *
 * @param valid             true if all blocks in the ledger are untampered and mathematically verified
 * @param totalBlocks       number of verified ledger entries
 * @param corruptedSequence sequence number where hash mismatch or sequence break occurred (null if valid)
 * @param expectedHash      expected cryptographic hash computed by the verifier (null if valid)
 * @param actualHash        actual hash found in the corrupted record (null if valid)
 * @param details           diagnostic summary or error rationale
 */
public record TamperVerificationResult(
    boolean valid,
    long totalBlocks,
    Long corruptedSequence,
    String expectedHash,
    String actualHash,
    String details
) {

    public static TamperVerificationResult success(long totalBlocks, String latestHash) {
        return new TamperVerificationResult(
            true,
            totalBlocks,
            null,
            latestHash,
            latestHash,
            "Audit ledger is intact and mathematically valid across " + totalBlocks + " block(s)."
        );
    }

    public static TamperVerificationResult corrupted(
        long totalBlocks,
        long corruptedSequence,
        String expectedHash,
        String actualHash,
        String reason
    ) {
        return new TamperVerificationResult(
            false,
            totalBlocks,
            corruptedSequence,
            expectedHash,
            actualHash,
            "Integrity violation at sequence " + corruptedSequence + ": " + reason
        );
    }
}
