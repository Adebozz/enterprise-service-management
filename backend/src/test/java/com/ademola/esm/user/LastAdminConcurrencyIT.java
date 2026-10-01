package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Two admins demote each other at the same moment. Without the pessimistic lock, both transactions
 * would see "another admin exists", both would commit, and nobody could administer the system.
 * With {@code SELECT ... FOR UPDATE}, the second transaction waits, re-reads, and is refused.
 */
@IntegrationTest
class LastAdminConcurrencyIT {

    @Autowired
    UserService userService;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.clean(jdbc);
    }

    @RepeatedTest(5)
    void concurrentMutualDemotionLeavesExactlyOneAdmin() throws Exception {
        User first = users.save(new User("first@example.com", "First", "h", Role.ADMIN));
        User second = users.save(new User("second@example.com", "Second", "h", Role.ADMIN));
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            futures.add(pool.submit(demote(first, start)));
            futures.add(pool.submit(demote(second, start)));
            start.countDown(); // release both threads together
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.DEMOTED, Outcome.REFUSED_LAST_ADMIN);
        assertThat(jdbc.queryForObject("select count(*) from users where role = 'ADMIN' and active", Integer.class))
                .isEqualTo(1);
    }

    private Callable<Outcome> demote(User admin, CountDownLatch start) {
        return () -> {
            // Each worker thread needs its own security context to pass @PreAuthorize.
            SecurityContextHolder.getContext()
                    .setAuthentication(new TestingAuthenticationToken("other-admin", null, "ROLE_ADMIN"));
            try {
                start.await();
                userService.update(admin.getId(), new UpdateUserRequest(null, Role.AGENT, null, 0L));
                return Outcome.DEMOTED;
            } catch (BusinessRuleException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED);
                return Outcome.REFUSED_LAST_ADMIN;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    enum Outcome {
        DEMOTED,
        REFUSED_LAST_ADMIN
    }
}
