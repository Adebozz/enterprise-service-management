package com.ademola.esm.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the refresh-token cookie.
 *
 * <ul>
 *   <li>{@code HttpOnly}: JavaScript (and therefore XSS) can't read it.
 *   <li>{@code Secure}: HTTPS only (browsers treat http://localhost as secure for development).
 *   <li>{@code SameSite=Strict}: never sent on requests initiated by other sites (CSRF).
 *   <li>{@code Path=/api/auth}: not attached to ordinary API calls, only refresh and logout.
 * </ul>
 */
@Component
class RefreshCookies {

    static final String PATH = "/api/auth";

    private final SecurityProperties.RefreshToken settings;

    RefreshCookies(SecurityProperties properties) {
        this.settings = properties.refreshToken();
    }

    String name() {
        return settings.cookieName();
    }

    /** Cookie lifetime = the token's remaining session lifetime at the moment it was issued. */
    ResponseCookie create(RefreshTokenService.IssuedToken token) {
        Duration maxAge = Duration.between(token.issuedAt(), token.expiresAt());
        return base(token.value()).maxAge(maxAge).build();
    }

    ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(settings.cookieName(), value)
                .httpOnly(true)
                .secure(settings.cookieSecure())
                .sameSite("Strict")
                .path(PATH);
    }
}
