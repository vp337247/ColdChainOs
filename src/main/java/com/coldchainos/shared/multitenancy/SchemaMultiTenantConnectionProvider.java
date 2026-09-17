package com.coldchainos.shared.multitenancy;

import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Hibernate MultiTenantConnectionProvider implementing Schema-per-Tenant in PostgreSQL.
 *
 * Rather than provisioning separate connection pools for each tenant (which exhausts DB connections),
 * this provider borrows connections from a single shared HikariCP pool and dynamically sets the PostgreSQL
 * search_path to the active tenant's schema on checkout, and resets it on release.
 */
@Component
@RequiredArgsConstructor
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        try (Statement statement = connection.createStatement()) {
            if (tenantIdentifier != null && !tenantIdentifier.isBlank()) {
                // Set the PostgreSQL search_path for this connection
                statement.execute("SET search_path TO \"" + tenantIdentifier + "\", public;");
            } else {
                statement.execute("SET search_path TO public;");
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path;");
        } catch (SQLException ignored) {
            // Best-effort reset before returning to Hikari pool
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return (T) this;
        }
        throw new IllegalArgumentException("Cannot unwrap to " + unwrapType.getName());
    }
}
