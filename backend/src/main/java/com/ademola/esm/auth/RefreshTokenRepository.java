package com.ademola.esm.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE}: if two requests present the same token simultaneously, the
     * second waits for the first to commit and then sees the token as already used.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> lockByTokenHash(String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revocationReason = :reason
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(UUID familyId, Instant now, RefreshToken.RevocationReason reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revocationReason = :reason
            where t.userId = :userId and t.revokedAt is null
            """)
    int revokeAllForUser(UUID userId, Instant now, RefreshToken.RevocationReason reason);
}
