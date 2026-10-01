package com.ademola.esm.auth;

import com.ademola.esm.user.PasswordChangedEvent;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token lifecycle: issue on login, rotate on every refresh, detect reuse, revoke.
 *
 * <p>Rotation means each token can be exchanged exactly once. If an attacker steals a token and
 * uses it, the legitimate client's next refresh presents an already-used token. That's the signal
 * to revoke the whole family (every token descended from that login), logging out both parties.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32; // 256 bits

    private final RefreshTokenRepository tokens;
    private final UserService users;
    private final SecurityProperties.RefreshToken settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenService(RefreshTokenRepository tokens, UserService users, SecurityProperties properties, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.settings = properties.refreshToken();
        this.clock = clock;
    }

    /** A freshly issued token. {@code value} goes into the cookie and is never stored. */
    record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {}

    record Rotation(User user, IssuedToken token) {}

    @Transactional
    IssuedToken startSession(UUID userId) {
        Instant now = clock.instant();
        return issue(userId, UUID.randomUUID(), now, now.plus(settings.sessionLifetime()));
    }

    /**
     * Exchanges a valid token for a new one in the same family.
     *
     * <p>{@code noRollbackFor}: when we detect reuse (or an inactive user), we revoke the family
     * <em>and then</em> reject the request. Without this, throwing the exception would roll back
     * the revocation too, and the stolen token family would stay alive.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = tokens.lockByTokenHash(hash(rawToken)).orElseThrow(InvalidRefreshTokenException::new);

        if (current.isRevoked() || current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException();
        }
        if (current.isUsed()) {
            if (now.isBefore(current.getUsedAt().plus(settings.reuseGracePeriod()))) {
                // Benign race: another tab refreshed with this token a moment ago and already
                // holds the replacement (the browser cookie jar is shared). Reject, don't punish.
                throw new InvalidRefreshTokenException();
            }
            int revoked = tokens.revokeFamily(current.getFamilyId(), now, RefreshToken.RevocationReason.REUSE_DETECTED);
            log.warn(
                    "SECURITY refresh token reuse detected: user={} family={} revokedTokens={}",
                    current.getUserId(),
                    current.getFamilyId(),
                    revoked);
            throw new InvalidRefreshTokenException();
        }

        User user = users.require(current.getUserId());
        if (!user.isActive()) {
            tokens.revokeFamily(current.getFamilyId(), now, RefreshToken.RevocationReason.USER_INACTIVE);
            throw new InvalidRefreshTokenException();
        }

        current.markUsed(now);
        // The replacement inherits the family's absolute expiry: refreshing can't extend a session.
        IssuedToken next = issue(user.getId(), current.getFamilyId(), now, current.getExpiresAt());
        return new Rotation(user, next);
    }

    /** Logout: revoke the presented token's family. Unknown tokens are ignored (idempotent). */
    @Transactional
    void endSession(String rawToken) {
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(token -> tokens.revokeFamily(
                        token.getFamilyId(), clock.instant(), RefreshToken.RevocationReason.LOGOUT));
    }

    /** Runs inside the password-change transaction: all sessions end together with the change. */
    @EventListener
    void onPasswordChanged(PasswordChangedEvent event) {
        int revoked = tokens.revokeAllForUser(
                event.userId(), clock.instant(), RefreshToken.RevocationReason.PASSWORD_CHANGED);
        log.info("Revoked {} refresh token(s) after password change for user {}", revoked, event.userId());
    }

    private IssuedToken issue(UUID userId, UUID familyId, Instant now, Instant expiresAt) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(userId, familyId, hash(value), now, expiresAt));
        return new IssuedToken(value, now, expiresAt);
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be available on every JVM", e);
        }
    }
}
