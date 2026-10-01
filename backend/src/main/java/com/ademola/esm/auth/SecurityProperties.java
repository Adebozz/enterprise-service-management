package com.ademola.esm.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed security configuration ({@code esm.security.*}). Binding to a record validates at startup:
 * a missing or weak JWT secret stops the application from starting instead of failing at the
 * first login.
 */
@ConfigurationProperties("esm.security")
public record SecurityProperties(
        Jwt jwt, RefreshToken refreshToken, @DefaultValue List<String> allowedOrigins) {

    public record Jwt(
            String secret,
            @DefaultValue("esm") String issuer,
            @DefaultValue("15m") Duration accessTokenTtl) {

        static final int MIN_SECRET_BYTES = 32; // HS256 needs a key of at least 256 bits

        public Jwt {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "esm.security.jwt.secret (ESM_JWT_SECRET) is required. Generate one with: openssl rand -base64 32");
            }
            if (decode(secret).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("esm.security.jwt.secret must be at least 32 bytes (base64-encoded)");
            }
        }

        byte[] secretBytes() {
            return decode(secret);
        }

        private static byte[] decode(String base64) {
            try {
                return Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("esm.security.jwt.secret must be base64-encoded", e);
            }
        }
    }

    public record RefreshToken(
            @DefaultValue("7d") Duration sessionLifetime,
            // Two tabs refreshing at the same moment present the same token; the loser must not be
            // treated as a thief. Reuse inside this window is rejected but does not revoke the family.
            @DefaultValue("30s") Duration reuseGracePeriod,
            @DefaultValue("esm_refresh") String cookieName,
            @DefaultValue("true") boolean cookieSecure) {}
}
