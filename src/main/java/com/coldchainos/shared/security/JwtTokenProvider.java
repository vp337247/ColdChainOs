package com.coldchainos.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * High-performance JWT token provider for multi-tenant stateless authentication.
 * Generates and cryptographically validates HMAC-SHA256 tokens containing user and tenant claims.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_ROLES = "roles";

    private final SecretKey signingKey;

    public JwtTokenProvider(
        @Value("${coldchainos.security.jwt.secret:coldchain-super-secret-cryptographic-signing-key-for-jwt-authentication-2026!}")
        String secret
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a signed Bearer JWT with embedded tenant boundary and RBAC roles.
     */
    public String generateToken(String userId, String tenantId, Set<String> roles, Duration validity) {
        Instant now = Instant.now();
        Instant expiry = now.plus(validity);

        return Jwts.builder()
            .subject(userId)
            .claim(CLAIM_TENANT_ID, tenantId)
            .claim(CLAIM_ROLES, new ArrayList<>(roles))
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(signingKey)
            .compact();
    }

    public String generateToken(String userId, String tenantId, Set<String> roles) {
        return generateToken(userId, tenantId, roles, Duration.ofHours(1));
    }

    /**
     * Validates signature and expiration of a JWT token string.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parses the JWT claims and extracts the authenticated ColdChainUserPrincipal.
     */
    public ColdChainUserPrincipal extractPrincipal(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String userId = claims.getSubject();
        String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
        List<?> rawRoles = claims.get(CLAIM_ROLES, List.class);
        Set<String> roles = new HashSet<>();
        if (rawRoles != null) {
            for (Object r : rawRoles) {
                roles.add(r.toString());
            }
        }

        return new ColdChainUserPrincipal(userId, tenantId, roles);
    }
}
