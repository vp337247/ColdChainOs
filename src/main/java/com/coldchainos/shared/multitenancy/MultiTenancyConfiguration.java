package com.coldchainos.shared.multitenancy;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultiTenancyConfiguration {

    @Bean
    public HibernatePropertiesCustomizer multiTenancyHibernateCustomizer(
        MultiTenantConnectionProvider<String> connectionProvider,
        CurrentTenantIdentifierResolver<String> tenantResolver
    ) {
        return hibernateProperties -> {
            hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantResolver);
        };
    }
}
