package com.ademola.esm.sla;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.MutableClock;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The SLA API: a ticket's clocks, queue badges and sorting, and admin targets. */
@IntegrationTest
class SlaApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    @Autowired
    MutableClock clock;

    UUID team;
    UUID category;
    User agent;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        team = fixtures.team("Network Team");
        category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        users.create("requester@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        users.create("outsider@example.com", Role.AGENT); // not in the team
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(team, agent.getId());
    }

    @AfterEach
    void restoreSeededTargets() {
        // sla_policies survives the cleaner, so undo any edits for the next test class
        jdbc.update("update sla_policies set response_minutes = 60, resolution_minutes = 480, version = 0"
                + " where ticket_type = 'INCIDENT' and priority = 'P2'");
    }

    @Test
    void supportSeesBothClocksOfATicket() {
        String id = incident("MEDIUM", "HIGH"); // P2: 1 h / 8 h
        clock.advance(Duration.ofMinutes(30));

        MvcTestResult result = get("agent@example.com", "/api/tickets/{id}/sla", id);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.clocks[0].metric").isEqualTo("RESPONSE");
        assertThat(result).bodyJson().extractingPath("$.clocks[0].status").isEqualTo("ON_TRACK");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.clocks[0].targetMinutes")
                .isEqualTo(60);
        // The test clock follows real time plus its offset, so allow for the seconds the test takes.
        int elapsed = JsonPath.read(body(result), "$.clocks[0].elapsedSeconds");
        int remaining = JsonPath.read(body(result), "$.clocks[0].remainingSeconds");
        assertThat(elapsed).isBetween(1800, 1810);
        assertThat(elapsed + remaining).isBetween(3599, 3600); // the 1 h target, split
        assertThat(result).bodyJson().extractingPath("$.clocks[0].dueAt").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.clocks[1].metric").isEqualTo("RESOLUTION");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.clocks[1].targetMinutes")
                .isEqualTo(480);
    }

    @Test
    void requestersAndUninvolvedAgentsCannotSeeServiceLevels() {
        String id = incident("MEDIUM", "HIGH");

        MvcTestResult requester = get("requester@example.com", "/api/tickets/{id}/sla", id);
        assertThat(requester).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(requester).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");

        assertThat(get("outsider@example.com", "/api/tickets/{id}/sla", id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theQueueShowsTheOpenTargetToStaffOnly() {
        String id = incident("MEDIUM", "HIGH");

        MvcTestResult forAgent = get("agent@example.com", "/api/tickets?view=TEAM", null);
        assertThat(forAgent)
                .bodyJson()
                .extractingPath("$.content[0].sla.metric")
                .isEqualTo("RESPONSE");
        assertThat(forAgent)
                .bodyJson()
                .extractingPath("$.content[0].sla.status")
                .isEqualTo("ON_TRACK");
        assertThat(forAgent).bodyJson().extractingPath("$.content[0].sla.dueAt").isNotNull();

        MvcTestResult forRequester = get("requester@example.com", "/api/tickets", null);
        assertThat(forRequester).bodyJson().extractingPath("$.content[0].id").isEqualTo(id);
        assertThat(forRequester).bodyJson().extractingPath("$.content[0].sla").isNull();
    }

    @Test
    void queueBadgesAgreeWithTheTicketsOwnSlaAtEveryStage() {
        String fresh = incident("MEDIUM", "HIGH"); // response due in 1 h
        clock.advance(Duration.ofMinutes(50));
        String waiting = incident("MEDIUM", "HIGH");
        jdbc.update(
                "update sla_clocks set state = 'PAUSED', elapsed_ms = 600000, running_since = null, due_at = null"
                        + " where work_item_id = ?::uuid",
                waiting);

        assertBadgeMatchesDetail(fresh, "AT_RISK"); // 50 of 60 minutes used, past 80%
        assertBadgeMatchesDetail(waiting, "PAUSED");

        clock.advance(Duration.ofMinutes(20));
        assertBadgeMatchesDetail(fresh, "BREACHED"); // 70 minutes, not yet recorded by the job
        assertBadgeMatchesDetail(waiting, "PAUSED");
    }

    @Test
    void theQueueCanBeSortedByTheNextDeadline() {
        String low = incident("LOW", "LOW"); // P4: response due in 8 h
        String high = incident("HIGH", "HIGH"); // P1: response due in 15 min
        String medium = incident("MEDIUM", "HIGH"); // P2: response due in 1 h

        MvcTestResult result = get("agent@example.com", "/api/tickets?view=TEAM&sort=slaDueAt,asc", null);

        assertThat(ids(result)).containsExactly(high, medium, low);
    }

    @Test
    void adminsListAndChangeTargetsWhichApplyToNewTicketsOnly() {
        String before = incident("MEDIUM", "HIGH");
        MvcTestResult list = get("admin@example.com", "/api/admin/sla-policies", null);
        assertThat(list).hasStatusOk();
        assertThat(list).bodyJson().extractingPath("$.length()").isEqualTo(8);
        assertThat(list).bodyJson().extractingPath("$[0].ticketType").isEqualTo("INCIDENT");
        assertThat(list).bodyJson().extractingPath("$[0].priority").isEqualTo("P1");
        String p2 = jdbc.queryForObject(
                "select id from sla_policies where ticket_type = 'INCIDENT' and priority = 'P2'", String.class);

        MvcTestResult updated =
                put("admin@example.com", p2, "{\"responseMinutes\": 30, \"resolutionMinutes\": 240, \"version\": 0}");

        assertThat(updated).hasStatusOk();
        assertThat(updated).bodyJson().extractingPath("$.responseMinutes").isEqualTo(30);
        assertThat(updated).bodyJson().extractingPath("$.version").isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_id is not null as has_actor, old_value->>'responseMinutes' as old_response,"
                        + " new_value->>'responseMinutes' as new_response from audit_events"
                        + " where action = 'SLA_POLICY_UPDATED' and entity_type = 'SLA_POLICY'");
        assertThat(audit)
                .containsEntry("has_actor", true)
                .containsEntry("old_response", "60")
                .containsEntry("new_response", "30");

        String after = incident("MEDIUM", "HIGH");
        assertThat(targetMinutes(before)).isEqualTo(60);
        assertThat(targetMinutes(after)).isEqualTo(30);
    }

    @Test
    void targetChangesAreValidatedAndVersioned() {
        String p2 = jdbc.queryForObject(
                "select id from sla_policies where ticket_type = 'INCIDENT' and priority = 'P2'", String.class);

        MvcTestResult inverted =
                put("admin@example.com", p2, "{\"responseMinutes\": 600, \"resolutionMinutes\": 60, \"version\": 0}");
        assertThat(inverted).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(inverted).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");

        assertThat(put("admin@example.com", p2, "{\"responseMinutes\": 0, \"resolutionMinutes\": 60, \"version\": 0}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put("admin@example.com", p2, "{\"responseMinutes\": 30, \"resolutionMinutes\": 60, \"version\": 7}"))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(put("agent@example.com", p2, "{\"responseMinutes\": 30, \"resolutionMinutes\": 60, \"version\": 0}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("agent@example.com", "/api/admin/sla-policies", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(put(
                        "admin@example.com",
                        UUID.randomUUID().toString(),
                        "{\"responseMinutes\": 30, \"resolutionMinutes\": 60, \"version\": 0}"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private void assertBadgeMatchesDetail(String id, String expected) {
        MvcTestResult queue = get("agent@example.com", "/api/tickets?view=TEAM&size=50", null);
        String badge = JsonPath.read(body(queue), "$.content[?(@.id == '%s')].sla.status".formatted(id))
                .toString();
        String detail =
                JsonPath.read(body(get("agent@example.com", "/api/tickets/{id}/sla", id)), "$.clocks[0].status");

        assertThat(detail).isEqualTo(expected);
        assertThat(badge).isEqualTo("[\"" + expected + "\"]");
    }

    private Integer targetMinutes(String ticketId) {
        return jdbc.queryForObject(
                "select target_minutes from sla_clocks where work_item_id = ?::uuid and metric = 'RESPONSE'",
                Integer.class,
                ticketId);
    }

    private String incident(String impact, String urgency) {
        MvcTestResult result = mvc.post()
                .uri("/api/incidents")
                .header(HttpHeaders.AUTHORIZATION, token("requester@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s",
                         "impact": "%s", "urgency": "%s"}
                        """.formatted(category, impact, urgency))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private MvcTestResult get(String email, String uri, String id) {
        return mvc.get()
                .uri(uri, id == null ? new Object[0] : new Object[] {id})
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .exchange();
    }

    private MvcTestResult put(String email, String policyId, String json) {
        return mvc.put()
                .uri("/api/admin/sla-policies/{id}", policyId)
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    /** Logs in at the current (possibly advanced) time, so the token is always fresh. */
    private String token(String email) {
        return loginOk(mvc, email).bearer();
    }

    private static List<String> ids(MvcTestResult result) {
        return JsonPath.read(body(result), "$.content[*].id");
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
