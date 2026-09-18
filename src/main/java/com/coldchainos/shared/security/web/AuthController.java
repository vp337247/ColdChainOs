package com.coldchainos.shared.security.web;

import com.coldchainos.shared.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Authentication and Token issuance controller.
 * Generates signed HMAC-SHA256 Bearer JWTs for multi-tenant users.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider tokenProvider;

    public record TokenRequest(String userId, String tenantId, Set<String> roles) {}

    @PostMapping("/token")
    public ResponseEntity<Map<String, String>> issueToken(@RequestBody TokenRequest request) {
        String token = tokenProvider.generateToken(
            request.userId(),
            request.tenantId(),
            request.roles() != null ? request.roles() : Set.of("LOGISTICS_OPERATOR"),
            Duration.ofHours(2)
        );

        return ResponseEntity.ok(Map.of(
            "token", token,
            "tokenType", "Bearer",
            "tenantId", request.tenantId(),
            "userId", request.userId()
        ));
    }
}
