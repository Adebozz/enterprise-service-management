package com.ademola.esm.auth;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, refresh and logout.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: {@link RefreshTokenService#rotate}
 * must own its transaction so that a reuse-detection revocation commits even though the request
 * then fails. An outer transaction here would roll that revocation back.
 */
@Service
class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    /** Hash of a random password; checked against when the email is unknown (see login). */
    private final String dummyHash;

    AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    record Session(TokenResponse body, RefreshTokenService.IssuedToken refreshToken) {}

    Session login(String email, String password) {
        Optional<User> found = users.findByEmail(User.normaliseEmail(email));

        // Always run exactly one BCrypt comparison, even for unknown emails. Otherwise "unknown
        // email" responses are measurably faster than "wrong password", revealing which emails
        // have accounts.
        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash);

        if (found.isEmpty() || !passwordMatches || !found.get().isActive()) {
            log.info("SECURITY login failed");
            throw new BusinessRuleException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        User user = found.get();
        log.info("SECURITY login succeeded user={}", user.getId());
        return new Session(tokenResponse(user), refreshTokens.startSession(user.getId()));
    }

    Session refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        // A fresh access token reflects the user's *current* role, so role changes and
        // deactivation take effect at the next refresh (at most one access-token lifetime).
        return new Session(tokenResponse(rotation.user()), rotation.token());
    }

    void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.endSession(rawRefreshToken);
        }
    }

    private TokenResponse tokenResponse(User user) {
        AccessTokenService.AccessToken token = accessTokens.issue(user);
        // From the token's own timestamps: re-reading the clock here would be a few ms later (899 s).
        long expiresIn = Duration.between(token.issuedAt(), token.expiresAt()).toSeconds();
        return new TokenResponse(token.value(), "Bearer", expiresIn, AuthenticatedUser.from(user));
    }
}
