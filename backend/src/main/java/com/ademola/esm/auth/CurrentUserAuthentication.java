package com.ademola.esm.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** An authenticated request whose principal is our {@link CurrentUser} rather than a raw JWT. */
class CurrentUserAuthentication extends AbstractAuthenticationToken {

    private final CurrentUser principal;
    private final transient Jwt token;

    CurrentUserAuthentication(CurrentUser principal, Jwt token) {
        super(List.of(new SimpleGrantedAuthority(principal.role().authority())));
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public CurrentUser getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return principal.id().toString();
    }
}
