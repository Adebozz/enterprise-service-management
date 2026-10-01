package com.ademola.esm.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One link in a refresh-token chain. Holds only the SHA-256 hash of the token value.
 *
 * <p>No {@code @Version}: concurrent use of the same token is serialised with a pessimistic row
 * lock instead (see {@link RefreshTokenRepository#lockByTokenHash}), because a conflict must be
 * decided in one go rather than detected and retried.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    enum RevocationReason {
        LOGOUT,
        REUSE_DETECTED,
        PASSWORD_CHANGED,
        USER_INACTIVE
    }

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 30)
    private RevocationReason revocationReason;

    protected RefreshToken() {}

    RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    boolean isUsed() {
        return usedAt != null;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    void markUsed(Instant now) {
        this.usedAt = now;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getFamilyId() {
        return familyId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getUsedAt() {
        return usedAt;
    }
}
