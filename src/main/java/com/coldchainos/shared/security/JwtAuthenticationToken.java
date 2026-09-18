package com.coldchainos.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Spring Security Authentication token holding the ColdChainUserPrincipal.
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final ColdChainUserPrincipal principal;

    public JwtAuthenticationToken(ColdChainUserPrincipal principal) {
        super(principal.getAuthorities());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
