package com.coldchainos.tenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "tenants", schema = "public")
@Getter
@Setter
@NoArgsConstructor
public class TenantJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    @Column(name = "organization_name", nullable = false, length = 128)
    private String organizationName;

    @Column(name = "schema_name", nullable = false, length = 64)
    private String schemaName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
