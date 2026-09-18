package com.coldchainos.shared.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multi-tenant authenticated security principal.
 * Carries the authenticated user identity, their tenant isolation boundary, and RBAC roles.
 */
@Getter
public class ColdChainUserPrincipal implements UserDetails {

    private final String userId;
    private final String tenantId;
    private final Set<String> roles;
    private final Collection<? extends GrantedAuthority> authorities;

    public ColdChainUserPrincipal(String userId, String tenantId, Set<String> roles) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.roles = roles != null ? Collections.unmodifiableSet(roles) : Collections.emptySet();
        this.authorities = this.roles.stream()
            .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null; // Stateless token authentication
    }

    @Override
    public String getUsername() {
        return userId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
