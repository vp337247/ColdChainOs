package com.coldchainos.tenant.application;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.tenant.domain.Tenant;
import com.coldchainos.tenant.domain.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates tenant provisioning and schema-level Flyway migrations.
 * Guarantees that each onboarded tenant receives an isolated PostgreSQL schema
 * with all current tables and constraints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final TenantRepository tenantRepository;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Provisions a new tenant:
     * 1. Runs versioned Flyway migrations targeting the new schema (Flyway creates the schema automatically)
     * 2. Persists tenant profile in public.tenants catalog
     *
     * Note: This method is intentionally NOT annotated with @Transactional.
     * Flyway manages its own DDL transactions; mixing Spring-managed transactions with Flyway
     * causes connection pool exhaustion and PostgreSQL catalog lock deadlocks.
     */
    public Tenant provisionTenant(TenantId tenantId, String organizationName) {
        Optional<Tenant> existing = tenantRepository.findById(tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String schemaName = tenantId.toSchemaName();
        log.info("Provisioning isolated schema '{}' for tenant '{}'", schemaName, tenantId);

        // 1. Run Flyway migration on the new schema (automatically creates schema if not present)
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schemaName)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load();
        flyway.migrate();

        // 2. Register tenant in public catalog
        Tenant tenant = Tenant.create(tenantId, organizationName);
        Tenant saved = tenantRepository.save(tenant);
        log.info("Tenant '{}' successfully provisioned with schema '{}'", tenantId, schemaName);
        return saved;
    }

    /**
     * Iterates across all registered tenants and migrates each schema to the latest Flyway version.
     * Invoked during deployment / application startup.
     */
    public void migrateAllTenants() {
        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Executing Flyway migrations across {} registered tenant schemas", tenants.size());

        for (Tenant tenant : tenants) {
            String schemaName = tenant.getId().toSchemaName();
            try {
                Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schemaName)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
                flyway.migrate();
                log.debug("Successfully migrated schema '{}'", schemaName);
            } catch (Exception e) {
                log.error("Failed to migrate schema '{}' for tenant '{}'", schemaName, tenant.getId(), e);
                throw new IllegalStateException("Failed to migrate schema: " + schemaName, e);
            }
        }
    }
}
