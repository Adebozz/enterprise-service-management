package com.ademola.esm.ticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.user.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Index regression tests: seed 100,000 tickets (a realistic medium-sized organisation), run EXPLAIN on <b>exactly the SQL the application
 * generates</b>, and assert the intended index is used. If a future change to the query (or a
 * dropped index) makes PostgreSQL fall back to scanning the table, the build fails.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlanIT {

    static final int TICKETS = 100_000;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NamedParameterJdbcTemplate named;

    UUID firstTeam;
    UUID someRequester;

    @BeforeAll
    void seed() {
        DatabaseCleaner.clean(jdbc);
        // 10 teams, each with one agent; 1,000 requesters; one category per team.
        jdbc.execute("""
                INSERT INTO teams (id, name, created_at, updated_at)
                SELECT gen_random_uuid(), 'Team ' || lpad(g::text, 2, '0'), now(), now() FROM generate_series(1, 10) g;
                INSERT INTO users (id, email, display_name, password_hash, role, created_at, updated_at)
                SELECT gen_random_uuid(), 'agent' || lpad(g::text, 2, '0') || '@perf.test', 'Agent ' || g, 'x', 'AGENT', now(), now()
                FROM generate_series(1, 10) g;
                INSERT INTO team_members (team_id, user_id, joined_at)
                SELECT t.id, u.id, now() FROM teams t
                JOIN users u ON u.email = 'agent' || substr(t.name, 6) || '@perf.test';
                INSERT INTO users (id, email, display_name, password_hash, role, created_at, updated_at)
                SELECT gen_random_uuid(), 'user' || g || '@perf.test', 'User ' || g, 'x', 'REQUESTER', now(), now()
                FROM generate_series(1, 1000) g;
                INSERT INTO categories (id, code, name, default_team_id, applies_to, created_at, updated_at)
                SELECT gen_random_uuid(), 'CAT_' || g, 'Category ' || g, t.id, 'ANY', now(), now()
                FROM generate_series(1, 10) g JOIN teams t ON t.name = 'Team ' || lpad(g::text, 2, '0');
                """);
        // A service desk that has been running for a while: 70% closed; in-progress, waiting and
        // resolved tickets have an owner; only NEW tickets (5%) sit unassigned in the queues.
        // 1 ticket in 500 mentions a printer (searches are usually selective). Team uses (g / 20) so it
        // is independent of status (g % 20); with g % 10 every NEW ticket would land in one team.
        jdbc.execute("""
                WITH t AS (SELECT array_agg(id ORDER BY name) a FROM teams),
                     ag AS (SELECT array_agg(id ORDER BY email) a FROM users WHERE role = 'AGENT'),
                     u AS (SELECT array_agg(id ORDER BY email) a FROM users WHERE role = 'REQUESTER'),
                     c AS (SELECT array_agg(id ORDER BY code) a FROM categories),
                     s AS (SELECT g,
                                  CASE WHEN g % 20 = 0 THEN 'NEW'
                                       WHEN g % 20 IN (1, 2) THEN 'IN_PROGRESS'
                                       WHEN g % 20 = 3 THEN 'WAITING_FOR_USER'
                                       WHEN g % 20 IN (4, 5) THEN 'RESOLVED'
                                       ELSE 'CLOSED' END AS status
                           FROM generate_series(1, $TICKETS) g)
                INSERT INTO work_items (id, reference, type, title, description, status, impact, urgency, priority,
                                        category_id, requester_id, assigned_team_id, assignee_id, created_at, updated_at)
                SELECT gen_random_uuid(), 'INC-' || (700000 + s.g), 'INCIDENT',
                       CASE WHEN s.g % 500 = 0 THEN 'Printer jammed ' || s.g
                            ELSE (ARRAY['Laptop', 'VPN', 'Email', 'Monitor', 'Phone', 'Badge', 'Payroll', 'Teams'])[1 + s.g % 8]
                                 || ' problem ' || s.g END,
                       'Reported issue number ' || s.g,
                       s.status, 'LOW', 'LOW', (ARRAY['P1', 'P2', 'P3', 'P4'])[1 + s.g % 4],
                       c.a[1 + s.g % 10], u.a[1 + s.g % 1000], t.a[1 + (s.g / 20) % 10],
                       CASE WHEN s.status = 'NEW' THEN NULL ELSE ag.a[1 + (s.g / 20) % 10] END,
                       now() - (s.g || ' minutes')::interval, now()
                FROM s, t, ag, u, c;
                INSERT INTO incidents (work_item_id) SELECT id FROM work_items;
                ANALYZE teams; ANALYZE users; ANALYZE categories; ANALYZE team_members; ANALYZE work_items;
                """.replace("$TICKETS", String.valueOf(TICKETS)));
        firstTeam = jdbc.queryForObject("SELECT id FROM teams ORDER BY name LIMIT 1", UUID.class);
        someRequester =
                jdbc.queryForObject("SELECT id FROM users WHERE role = 'REQUESTER' ORDER BY email LIMIT 1", UUID.class);
    }

    @AfterAll
    void clean() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void unassignedQueueUsesThePartialIndex() {
        CurrentUser agent = new CurrentUser(UUID.randomUUID(), Role.AGENT, "Agent");
        TicketListQuery query = TicketListQuery.build(
                agent, Set.of(firstTeam), params(TicketView.UNASSIGNED, null), TicketListQuery.NameMatches.NONE);

        assertThat(plan(query.page(PageRequest.of(0, 20, Sort.by("createdAt")))))
                .contains("work_items_unassigned_queue_idx");
    }

    @Test
    void fullTextSearchUsesTheGinIndex() {
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), Role.ADMIN, "Admin");
        TicketListQuery query =
                TicketListQuery.build(admin, Set.of(), params(null, "printer"), TicketListQuery.NameMatches.NONE);

        assertThat(plan(query.page(PageRequest.of(0, 20)))).contains("work_items_search_idx");
        assertThat(plan(query.count())).contains("work_items_search_idx");
    }

    @Test
    void myTicketsUsesTheRequesterIndex() {
        CurrentUser requester = new CurrentUser(someRequester, Role.REQUESTER, "User");
        TicketListQuery query = TicketListQuery.build(
                requester, Set.of(), params(TicketView.REQUESTED, null), TicketListQuery.NameMatches.NONE);

        assertThat(plan(query.page(PageRequest.of(0, 20)))).contains("work_items_requester_created_idx");
    }

    @Test
    void referenceLookupUsesTheUniqueIndex() {
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), Role.ADMIN, "Admin");
        TicketListQuery query =
                TicketListQuery.build(admin, Set.of(), params(null, "INC-710000"), TicketListQuery.NameMatches.NONE);

        assertThat(plan(query.page(PageRequest.of(0, 20)))).contains("work_items_reference_uk");
    }

    private String plan(TicketListQuery.Sql sql) {
        return named.queryForObject("EXPLAIN (FORMAT JSON) " + sql.text(), sql.params(), String.class);
    }

    private static TicketSearchParams params(TicketView view, String q) {
        return new TicketSearchParams(view, null, null, null, null, null, null, null, null, null, q);
    }
}
