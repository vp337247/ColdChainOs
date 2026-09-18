package com.coldchainos.shared.security;

import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.multitenancy.TenantFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Intercepts incoming HTTP requests to validate Bearer JWT tokens and enforce
 * multi-tenant security boundaries before dispatching to controllers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            if (tokenProvider.validateToken(token)) {
                ColdChainUserPrincipal principal = tokenProvider.extractPrincipal(token);

                // Anti-Spoofing Check: If X-Tenant-ID header is present, it must strictly match token tenant
                String headerTenant = request.getHeader(TenantFilter.TENANT_HEADER);
                if (headerTenant != null && !headerTenant.isBlank() && !headerTenant.equals(principal.getTenantId())) {
                    log.warn("Cross-tenant spoofing attempt detected! Token tenant '{}' != Header tenant '{}'",
                        principal.getTenantId(), headerTenant);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-tenant access forbidden");
                    return;
                }

                // Establish Security Context
                JwtAuthenticationToken authentication = new JwtAuthenticationToken(principal);
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Bind authenticated tenant to TenantContext & MDC
                TenantContext.setTenantId(principal.getTenantId());
                MDC.put("tenantId", principal.getTenantId());
                MDC.put("userId", principal.getUserId());
            } else {
                log.debug("Invalid or expired Bearer token provided in Authorization header");
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Clean up request-scoped tenant & user context
            MDC.remove("userId");
        }
    }
}
