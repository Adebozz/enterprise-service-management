package com.ademola.esm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.MutableClock;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two browser tabs refresh with the same cookie at the same moment. The row lock must ensure exactly
 * one rotation happens (not two valid successors), and the loser must not revoke the session.
 */
@IntegrationTest
class RefreshConcurrencyIT {

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
    }

    @RepeatedTest(5)
    void concurrentRefreshWithTheSameTokenRotatesExactlyOnce() throws Exception {
        User user = testUsers.create("agent@example.com", Role.AGENT);
        String token = refreshTokens.startSession(user.getId()).value();
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(attemptRotation(token, start)));
            }
            start.countDown();
        }

        int successes = 0;
        for (Future<Boolean> result : results) {
            successes += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        assertThat(successes).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class))
                .as("one original + exactly one successor")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from refresh_tokens where revoked_at is not null", Integer.class))
                .as("benign race inside the grace window must not revoke the session")
                .isZero();
    }

    private Callable<Boolean> attemptRotation(String token, CountDownLatch start) {
        return () -> {
            start.await();
            try {
                refreshTokens.rotate(token);
                return true;
            } catch (InvalidRefreshTokenException e) {
                return false;
            }
        };
    }
}
