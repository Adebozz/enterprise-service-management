package com.ademola.esm.ticket.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
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

/** Two team leads assign the same ticket to different agents at the same moment. */
@IntegrationTest
class AssignmentConcurrencyIT {

    @Autowired
    TicketAssignmentService assignments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    @Autowired
    TestUsers users;

    UUID team;
    UUID ticketId;
    CurrentUser leadA;
    CurrentUser leadB;
    User agentA;
    User agentB;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        team = fixtures.team("Network Team");
        UUID category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        User requester = users.create("req@example.com", Role.REQUESTER);
        agentA = users.create("agent.a@example.com", Role.AGENT);
        agentB = users.create("agent.b@example.com", Role.AGENT);
        User la = users.create("lead.a@example.com", Role.TEAM_LEAD);
        User lb = users.create("lead.b@example.com", Role.TEAM_LEAD);
        for (User u : new User[] {agentA, agentB, la, lb}) {
            fixtures.member(team, u.getId());
        }
        leadA = new CurrentUser(la.getId(), Role.TEAM_LEAD, "Lead A");
        leadB = new CurrentUser(lb.getId(), Role.TEAM_LEAD, "Lead B");

        ticketId = UUID.randomUUID();
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, requester_id, assigned_team_id, created_at, updated_at, version)"
                        + " values (?, 'INC-' || lpad(nextval('incident_ref_seq')::text, 6, '0'), 'INCIDENT', 't', 'd',"
                        + " 'NEW', 'LOW', 'LOW', 'P4', ?, ?, ?, now(), now(), 0)",
                ticketId,
                category,
                requester.getId(),
                team);
        jdbc.update("insert into incidents (work_item_id) values (?)", ticketId);
    }

    @RepeatedTest(5)
    void exactlyOneLeadsAssignmentWins() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            outcomes.add(pool.submit(attempt(leadA, agentA.getId(), start)));
            outcomes.add(pool.submit(attempt(leadB, agentB.getId(), start)));
            start.countDown();
        }

        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }
        assertThat(results).containsOnlyOnce("WON").contains("CONFLICT");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'TICKET_ASSIGNMENT_CHANGED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from work_items where id = ?", String.class, ticketId))
                .isEqualTo("ASSIGNED");
    }

    private Callable<String> attempt(CurrentUser lead, UUID assignee, CountDownLatch start) {
        return () -> {
            SecurityContextHolder.getContext()
                    .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            lead, null, List.of(new SimpleGrantedAuthority("ROLE_TEAM_LEAD"))));
            try {
                start.await();
                assignments.assign(lead, ticketId, new AssignmentRequest(team, assignee, 0L));
                return "WON";
            } catch (StaleVersionException | ObjectOptimisticLockingFailureException conflict) {
                return "CONFLICT";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
