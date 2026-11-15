package com.ademola.esm.auth;

import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.TooManyRequestsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after too many failed sign-ins for one account, or from one
 * address, within the window, further attempts are refused (429) until the oldest failure ages out.
 *
 * <p>The check runs before the password is verified, and the same answer is given whether or not
 * the account exists, so throttling reveals nothing about which emails have accounts. A successful
 * sign-in clears that account's failures (not the address's: one success shouldn't excuse a spray).
 *
 * <p>This is the application's own line of defence. In production a rate rule at the edge (AWS WAF)
 * would sit in front of it, absorbing floods before they reach the database.
 */
@Component
class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final JdbcTemplate jdbc;
    private final LoginThrottleProperties limits;
    private final Clock clock;

    LoginThrottle(JdbcTemplate jdbc, LoginThrottleProperties limits, Clock clock) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.clock = clock;
    }

    /** @throws TooManyRequestsException if this account or address has used up its failures */
    void check(String normalisedEmail, String address) {
        Instant since = clock.instant().minus(limits.window());
        Instant accountWait = waitUntil("account_hash", hash(normalisedEmail), limits.maxFailuresPerAccount(), since);
        Instant addressWait = waitUntil("address", address, limits.maxFailuresPerAddress(), since);
        Instant until = later(accountWait, addressWait);
        if (until != null) {
            log.warn("SECURITY login throttled address={}", address);
            Duration retryAfter = Duration.between(clock.instant(), until);
            throw new TooManyRequestsException(
                    ErrorCode.TOO_MANY_LOGIN_ATTEMPTS,
                    "Too many failed sign-ins. Try again in %d minutes."
                            .formatted(Math.max(1, (retryAfter.toSeconds() + 59) / 60)),
                    retryAfter.isNegative() ? Duration.ZERO : retryAfter);
        }
    }

    void recordFailure(String normalisedEmail, String address) {
        Instant now = clock.instant();
        // Keep the table to the window: whatever is older can no longer count against anyone.
        jdbc.update("delete from login_failures where failed_at < ?", Timestamp.from(now.minus(limits.window())));
        jdbc.update(
                "insert into login_failures (account_hash, address, failed_at) values (?, ?, ?)",
                hash(normalisedEmail),
                address,
                Timestamp.from(now));
    }

    void recordSuccess(String normalisedEmail) {
        jdbc.update("delete from login_failures where account_hash = ?", hash(normalisedEmail));
    }

    /**
     * When the key may try again, or {@code null} if it hasn't reached its limit: once the
     * {@code limit}-th most recent failure leaves the window, one more attempt is allowed.
     */
    private Instant waitUntil(String column, String key, int limit, Instant since) {
        return jdbc
                .query(
                        "select failed_at from login_failures where " + column + " = ? and failed_at >= ?"
                                + " order by failed_at desc offset ? limit 1",
                        (rs, row) -> rs.getTimestamp(1).toInstant(),
                        key,
                        Timestamp.from(since),
                        limit - 1)
                .stream()
                .findFirst()
                .map(oldestCounted -> oldestCounted.plus(limits.window()))
                .orElse(null);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    static String hash(String normalisedEmail) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(normalisedEmail.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
