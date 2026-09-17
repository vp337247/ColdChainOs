package com.coldchainos.shared.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Web filter that extracts the tenant identifier from the X-Tenant-ID HTTP header
 * and binds it to the TenantContext for the lifecycle of the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String tenantHeader = request.getHeader(TENANT_HEADER);

        try {
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                TenantContext.setTenantId(tenantHeader);
            }
            filterChain.doFilter(request, response);
        } finally {
            // Crucial: Clear thread local to prevent contamination in thread pools
            TenantContext.clear();
        }
    }
}
