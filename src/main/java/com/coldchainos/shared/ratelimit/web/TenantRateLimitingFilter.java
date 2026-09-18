package com.coldchainos.shared.ratelimit.web;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.ratelimit.RateLimitResult;
import com.coldchainos.shared.ratelimit.TenantRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts incoming API requests and applies distributed token bucket rate limiting per tenant.
 * Returns HTTP 429 Too Many Requests with RFC-standard headers when tenant quota is exceeded.
 */
@Component
public class TenantRateLimitingFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_URI_PATTERN = Pattern.compile("^/api/v1/tenants/([^/]+)/.*");

    public static final String HEADER_RATE_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_RATE_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private final TenantRateLimiter rateLimiter;

    public TenantRateLimitingFilter(TenantRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        TenantId tenantId = resolveTenantId(request);

        if (tenantId != null) {
            RateLimitResult result = rateLimiter.tryAcquire(tenantId);

            response.setHeader(HEADER_RATE_LIMIT, String.valueOf(result.capacity()));
            response.setHeader(HEADER_RATE_REMAINING, String.valueOf(result.remainingTokens()));

            if (!result.allowed()) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(String.format(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded for tenant '%s'.\",\"retryAfterSeconds\":%d}",
                    tenantId.value(),
                    result.retryAfterSeconds()
                ));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private TenantId resolveTenantId(HttpServletRequest request) {
        // 1. Prefer authenticated TenantContext
        if (TenantContext.getTenantId() != null) {
            return TenantContext.getTenantId();
        }

        // 2. Fallback to extracting from URI path pattern
        Matcher matcher = TENANT_URI_PATTERN.matcher(request.getRequestURI());
        if (matcher.matches()) {
            return TenantId.of(matcher.group(1));
        }

        return null;
    }
}
