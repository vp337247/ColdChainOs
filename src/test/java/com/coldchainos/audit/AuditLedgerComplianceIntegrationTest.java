package com.coldchainos.audit;

import com.coldchainos.audit.application.AuditHashCalculator;
import com.coldchainos.audit.application.AuditLedgerService;
import com.coldchainos.audit.application.AuditLedgerVerificationService;
import com.coldchainos.audit.application.TamperVerificationResult;
import com.coldchainos.audit.infrastructure.AuditLedgerEntryJpaEntity;
import com.coldchainos.audit.infrastructure.AuditLedgerJpaRepository;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 Integration Test: FDA 21 CFR Part 11 Cryptographic Audit Ledger & Tamper Detection.
 *
 * Verifies:
 * 1. Monotonic sequential appending and SHA-256 predecessor hash chaining.
 * 2. Successful verification of an intact cryptographic ledger.
 * 3. Immediate tamper detection when database rows are modified via out-of-band SQL mutations.
 * 4. Multi-tenant schema isolation of cryptographic chains.
 */
@SpringBootTest
public class AuditLedgerComplianceIntegrationTest {

    @Autowired
    private AuditLedgerService ledgerService;

    @Autowired
    private AuditLedgerVerificationService verificationService;

    @Autowired
    private AuditLedgerJpaRepository ledgerRepository;

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TenantId tenantAlpha = TenantId.of("audit_tenant_alpha");
    private final TenantId tenantBeta = TenantId.of("audit_tenant_beta");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantAlpha, "Audit Alpha Laboratories");
        provisioningService.provisionTenant(tenantBeta, "Audit Beta Pharmaceuticals");

        jdbcTemplate.execute("TRUNCATE TABLE " + tenantAlpha.toSchemaName() + ".compliance_audit_ledger CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE " + tenantBeta.toSchemaName() + ".compliance_audit_ledger CASCADE");
    }

    @Test
    @DisplayName("1. Appending audit records should produce monotonic sequences and link predecessor SHA-256 hashes")
    void shouldAppendMonotonicEntriesAndLinkPrevHashesCleanly() {
        UUID shipmentId = UUID.randomUUID();

        // Block 0: Genesis block
        AuditLedgerEntryJpaEntity b0 = ledgerService.appendEvent(
            tenantAlpha,
            shipmentId,
            "Shipment",
            "ShipmentCreatedEvent",
            "{\"status\":\"CREATED\",\"tempCategory\":\"REFRIGERATED_2_TO_8\"}",
            "OPERATOR_SARAH",
            "SIG_RSA_SHA256_SARAH_001"
        );

        // Block 1: First mutation
        AuditLedgerEntryJpaEntity b1 = ledgerService.appendEvent(
            tenantAlpha,
            shipmentId,
            "Shipment",
            "CarrierAssignedEvent",
            "{\"carrierId\":\"DHL_COLD_CHAIN\",\"leg\":1}",
            "DISPATCHER_ALEX",
            "SIG_RSA_SHA256_ALEX_002"
        );

        // Block 2: Transit started
        AuditLedgerEntryJpaEntity b2 = ledgerService.appendEvent(
            tenantAlpha,
            shipmentId,
            "Shipment",
            "TransitStartedEvent",
            "{\"status\":\"IN_TRANSIT\",\"departureDepot\":\"FRA\"}",
            "DRIVER_HANS",
            "SIG_RSA_SHA256_HANS_003"
        );

        // Assert: Sequences are monotonically 0, 1, 2
        assertThat(b0.getSequenceNumber()).isEqualTo(0L);
        assertThat(b1.getSequenceNumber()).isEqualTo(1L);
        assertThat(b2.getSequenceNumber()).isEqualTo(2L);

        // Assert: Cryptographic hash linkage
        assertThat(b0.getPrevHash()).isEqualTo(AuditHashCalculator.GENESIS_HASH);
        assertThat(b1.getPrevHash()).isEqualTo(b0.getHash());
        assertThat(b2.getPrevHash()).isEqualTo(b1.getHash());

        // Assert: Hashes are valid non-empty 64-char hex strings
        assertThat(b0.getHash()).hasSize(64);
        assertThat(b1.getHash()).hasSize(64);
        assertThat(b2.getHash()).hasSize(64);
    }

    @Test
    @DisplayName("2. Unaltered audit ledger passes 100% cryptographic integrity verification")
    void shouldVerifyIntactAuditLedgerSuccessfully() {
        UUID shipmentId = UUID.randomUUID();

        // Append 5 sequential events
        for (int i = 0; i < 5; i++) {
            ledgerService.appendEvent(
                tenantAlpha,
                shipmentId,
                "Shipment",
                "TelemetryLoggedEvent",
                "{\"temperatureCelsius\": 4.5" + i + "}",
                "TELEMETRY_INGESTION_SERVICE",
                "SIG_SYSTEM_INGEST_00" + i
            );
        }

        // Act: Run verification engine
        TamperVerificationResult result = verificationService.verifyLedgerIntegrity(tenantAlpha);

        // Assert: Perfectly valid
        assertThat(result.valid()).isTrue();
        assertThat(result.totalBlocks()).isEqualTo(5L);
        assertThat(result.corruptedSequence()).isNull();
    }

    @Test
    @DisplayName("3. Tamper Detection Lab: Out-of-band SQL modification of a historical block triggers immediate verification failure")
    void shouldDetectDatabaseTamperingWhenRowPayloadIsModified() {
        UUID shipmentId = UUID.randomUUID();

        // Append 4 events (sequences 0, 1, 2, 3)
        for (int i = 0; i < 4; i++) {
            ledgerService.appendEvent(
                tenantAlpha,
                shipmentId,
                "Shipment",
                "ThermalReadingLoggedEvent",
                "{\"readingIndex\":" + i + ",\"celsius\":4.0}",
                "SENSOR_LOGGER",
                "SIG_SENSOR_" + i
            );
        }

        // Verify initially valid
        assertThat(verificationService.verifyLedgerIntegrity(tenantAlpha).valid()).isTrue();

        // SIMULATE TAMPERING: A rogue DBA or attacker modifies sequence #2 directly in PostgreSQL
        String schema = tenantAlpha.toSchemaName();
        int updatedRows = jdbcTemplate.update(
            "UPDATE " + schema + ".compliance_audit_ledger SET payload_json = ? WHERE sequence_number = ?",
            "{\"readingIndex\":2,\"celsius\":25.0,\"tampered\":true}",
            2L
        );
        assertThat(updatedRows).isEqualTo(1);

        // Act: Run verification engine
        TamperVerificationResult tamperResult = verificationService.verifyLedgerIntegrity(tenantAlpha);

        // Assert: Tampering caught and exact block flagged
        assertThat(tamperResult.valid()).isFalse();
        assertThat(tamperResult.corruptedSequence()).isEqualTo(2L);
        assertThat(tamperResult.details()).contains("Cryptographic hash mismatch");
    }

    @Test
    @DisplayName("4. Multi-Tenant Cryptographic Isolation: Tenant Alpha and Tenant Beta have isolated ledgers and genesis blocks")
    void shouldEnforceMultiTenantLedgerIsolation() {
        UUID shipmentA = UUID.randomUUID();
        UUID shipmentB = UUID.randomUUID();

        // Append to Alpha
        AuditLedgerEntryJpaEntity alphaEntry = ledgerService.appendEvent(
            tenantAlpha,
            shipmentA,
            "Shipment",
            "CreatedAlphaEvent",
            "{\"tenant\":\"alpha\"}",
            "ADMIN_ALPHA",
            "SIG_ALPHA_01"
        );

        // Append to Beta
        AuditLedgerEntryJpaEntity betaEntry = ledgerService.appendEvent(
            tenantBeta,
            shipmentB,
            "Shipment",
            "CreatedBetaEvent",
            "{\"tenant\":\"beta\"}",
            "ADMIN_BETA",
            "SIG_BETA_01"
        );

        // Assert: Both are sequence 0 with independent genesis blocks
        assertThat(alphaEntry.getSequenceNumber()).isEqualTo(0L);
        assertThat(betaEntry.getSequenceNumber()).isEqualTo(0L);
        assertThat(alphaEntry.getPrevHash()).isEqualTo(AuditHashCalculator.GENESIS_HASH);
        assertThat(betaEntry.getPrevHash()).isEqualTo(AuditHashCalculator.GENESIS_HASH);

        // Assert: Both chains verify independently
        assertThat(verificationService.verifyLedgerIntegrity(tenantAlpha).valid()).isTrue();
        assertThat(verificationService.verifyLedgerIntegrity(tenantBeta).valid()).isTrue();
    }
}
