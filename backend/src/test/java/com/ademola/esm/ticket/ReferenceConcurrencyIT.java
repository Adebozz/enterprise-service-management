package com.ademola.esm.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.ticket.incident.CreateIncidentRequest;
import com.ademola.esm.ticket.incident.IncidentService;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.user.Role;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Many people raising incidents at the same instant must never receive the same reference. */
@IntegrationTest
class ReferenceConcurrencyIT {

    static final int CONCURRENT_REQUESTS = 20;

    @Autowired
    IncidentService incidents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    @Autowired
    TestUsers users;

    UUID category;
    CurrentUser requester;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        UUID team = fixtures.team("Network Team");
        category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        requester =
                new CurrentUser(users.create("req@example.com", Role.REQUESTER).getId(), Role.REQUESTER, "Req");
    }

    @Test
    void concurrentCreationsGetDistinctReferences() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                futures.add(pool.submit(create(start, i)));
            }
            start.countDown();
        }

        Set<String> references = new HashSet<>();
        for (Future<String> future : futures) {
            references.add(future.get(60, TimeUnit.SECONDS));
        }
        assertThat(references).hasSize(CONCURRENT_REQUESTS).allMatch(ref -> ref.matches("INC-\\d{6}"));
        assertThat(jdbc.queryForObject("select count(distinct reference) from work_items", Integer.class))
                .isEqualTo(CONCURRENT_REQUESTS);
    }

    private Callable<String> create(CountDownLatch start, int n) {
        return () -> {
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            requester, null, List.of(new SimpleGrantedAuthority("ROLE_REQUESTER"))));
            try {
                start.await();
                return incidents
                        .create(
                                requester,
                                new CreateIncidentRequest(
                                        "Outage " + n, "Details", category, null, Impact.LOW, Urgency.LOW, null))
                        .reference();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
