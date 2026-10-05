package com.ademola.esm.ticket.transition;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.ticket.incident.ResolutionCode;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Two agents act on the same ticket version at the same moment (one resolves, one sets it to
 * waiting). Exactly one change may win.
 *
 * <p>Because both threads load the ticket before either commits, the explicit version check passes
 * for both; the loser is stopped by Hibernate's {@code @Version} condition in the UPDATE
 * ({@code ... WHERE id = ? AND version = 0} matches no row). Observed in 30/30 runs during
 * development. The explicit check (client sent an old version after the winner committed) is
 * covered by {@code TicketTransitionApiIT.staleVersionIsAConflict}. Both map to HTTP 409.
 */
@IntegrationTest
class TransitionConcurrencyIT {

    @Autowired
    TicketTransitionService transitions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    @Autowired
    TestUsers users;

    UUID ticketId;
    CurrentUser agentA;
    CurrentUser agentB;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        UUID team = fixtures.team("Network Team");
        UUID category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        User requester = users.create("req@example.com", Role.REQUESTER);
        User a = users.create("a@example.com", Role.AGENT);
        User b = users.create("b@example.com", Role.AGENT);
        fixtures.member(team, a.getId());
        fixtures.member(team, b.getId());
        agentA = new CurrentUser(a.getId(), Role.AGENT, "A");
        agentB = new CurrentUser(b.getId(), Role.AGENT, "B");

        ticketId = UUID.randomUUID();
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, requester_id, assigned_team_id, assignee_id, created_at, updated_at, version)"
                        + " values (?, 'INC-' || lpad(nextval('incident_ref_seq')::text, 6, '0'), 'INCIDENT', 't', 'd',"
                        + " 'IN_PROGRESS', 'LOW', 'LOW', 'P4', ?, ?, ?, ?, now(), now(), 0)",
                ticketId,
                category,
                requester.getId(),
                team,
                a.getId());
        jdbc.update("insert into incidents (work_item_id) values (?)", ticketId);
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoSimultaneousChangesWins() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            outcomes.add(pool.submit(
                    attempt(agentA, new TransitionRequest("RESOLVED", 0L, null, ResolutionCode.FIXED, "done"), start)));
            outcomes.add(pool.submit(
                    attempt(agentB, new TransitionRequest("WAITING_FOR_USER", 0L, "need info", null, null), start)));
            start.countDown();
        }

        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }
        assertThat(results).containsOnlyOnce("WON").contains("CONFLICT");
        assertThat(jdbc.queryForObject("select version from work_items where id = ?", Long.class, ticketId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'TICKET_STATUS_CHANGED'", Integer.class))
                .as("the loser's audit entry was rolled back with its change")
                .isEqualTo(1);
    }

    private Callable<String> attempt(CurrentUser user, TransitionRequest request, CountDownLatch start) {
        return () -> {
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            user, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
            try {
                start.await();
                transitions.transition(user, ticketId, request);
                return "WON";
            } catch (StaleVersionException | ObjectOptimisticLockingFailureException conflict) {
                return "CONFLICT";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
