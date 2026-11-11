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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** SLA clocks driven by real tickets through the HTTP API, plus the breach job. */
@IntegrationTest
class SlaLifecycleIT {

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

    @Autowired
    SlaBreachService breaches;

    UUID team;
    UUID incidentCategory;
    UUID requestCategory;
    User agent;
    String requesterToken;
    String agentToken;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        team = fixtures.team("Network Team");
        incidentCategory = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        requestCategory = fixtures.category("ACCESS", "Access", null, team, "SERVICE_REQUEST");
        users.create("requester@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        fixtures.member(team, agent.getId());
        logIn();
    }

    /** Moves time on; access tokens last 15 minutes, so everyone logs in again. */
    void advance(Duration duration) {
        clock.advance(duration);
        logIn();
    }

    void logIn() {
        requesterToken = loginOk(mvc, "requester@example.com").bearer();
        agentToken = loginOk(mvc, "agent@example.com").bearer();
    }

    @Test
    void aNewTicketStartsBothClocksWithItsPolicyTargets() {
        String incident = createIncident(); // MEDIUM impact x HIGH urgency = P2: 1 h / 8 h
        String request =
                created("/api/service-requests", """
                {"title": "VPN access", "description": "For the new starter", "categoryId": "%s",
                 "impact": "LOW", "urgency": "LOW"}
                """.formatted(requestCategory)); // P4 request: 1 day / 10 days

        assertThat(clock(incident, "RESPONSE"))
                .containsEntry("state", "RUNNING")
                .containsEntry("target_minutes", 60);
        assertThat(clock(incident, "RESOLUTION")).containsEntry("target_minutes", 480);
        assertThat(clock(request, "RESPONSE")).containsEntry("target_minutes", 1440);
        assertThat(clock(request, "RESOLUTION")).containsEntry("target_minutes", 14400);
        assertThat(jdbc.queryForObject(
                        "select due_at - running_since = interval '1 hour' from sla_clocks"
                                + " where work_item_id = ?::uuid and metric = 'RESPONSE'",
                        Boolean.class,
                        incident))
                .isTrue();
    }

    @Test
    void theClocksFollowTheIncidentThroughItsLifecycle() {
        String id = assignedIncident();

        assertThat(move(agentToken, id, 0, "IN_PROGRESS", null)).hasStatusOk();
        assertThat(clock(id, "RESPONSE")).containsEntry("state", "COMPLETED");
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "RUNNING");

        assertThat(move(agentToken, id, 1, "WAITING_FOR_USER", "{\"reason\": \"Which floor?\"}"))
                .hasStatusOk();
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "PAUSED");

        advance(Duration.ofHours(30)); // far beyond the 8 h target, but none of it counts
        assertThat(comment(requesterToken, id, "PUBLIC", "Floor 3")).hasStatus(HttpStatus.CREATED);

        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "RUNNING");
        assertThat((Long) clock(id, "RESOLUTION").get("elapsed_ms"))
                .isLessThan(Duration.ofMinutes(1).toMillis());

        assertThat(move(agentToken, id, 3, "RESOLVED", """
                        {"resolutionCode": "FIXED", "notes": "Rebooted the access point"}
                        """)).hasStatusOk();
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "COMPLETED");

        assertThat(move(requesterToken, id, 4, "IN_PROGRESS", "{\"reason\": \"Down again\"}"))
                .hasStatusOk();
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "RUNNING");
        assertThat(clock(id, "RESOLUTION").get("breached_at")).isNull();
        assertThat(breachAudits()).isEmpty();
    }

    @Test
    void aRequesterReplyResumesAWaitingTicketAndTheHistorySaysWhy() {
        String id = waitingIncident();

        assertThat(comment(requesterToken, id, "PUBLIC", "Floor 3")).hasStatus(HttpStatus.CREATED);

        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        Map<String, Object> resumed = jdbc.queryForMap(
                "select actor_id, new_value->>'status' as status, metadata->>'trigger' as trigger,"
                        + " metadata->>'transition' as transition from audit_events"
                        + " where action = 'TICKET_STATUS_CHANGED' and entity_id = ?::uuid"
                        + " order by occurred_at desc, id desc limit 1",
                id);
        assertThat(resumed)
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("trigger", "REQUESTER_REPLY")
                .containsEntry("transition", "Resume");
        assertThat(resumed.get("actor_id")).isNotNull(); // the requester's reply caused it
    }

    @Test
    void supportRepliesAndInternalNotesDoNotResumeAWaitingTicket() {
        String id = waitingIncident();

        assertThat(comment(agentToken, id, "PUBLIC", "Any update?")).hasStatus(HttpStatus.CREATED);
        assertThat(comment(agentToken, id, "INTERNAL", "Chased by phone")).hasStatus(HttpStatus.CREATED);

        assertThat(status(id)).isEqualTo("WAITING_FOR_USER");
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "PAUSED");
    }

    @Test
    void aPublicSupportReplyIsTheFirstResponseEvenBeforeWorkStarts() {
        String id = createIncident();

        assertThat(comment(agentToken, id, "INTERNAL", "Looking into it")).hasStatus(HttpStatus.CREATED);
        assertThat(clock(id, "RESPONSE")).containsEntry("state", "RUNNING");

        assertThat(comment(agentToken, id, "PUBLIC", "We're on it")).hasStatus(HttpStatus.CREATED);

        assertThat(status(id)).isEqualTo("NEW");
        assertThat(clock(id, "RESPONSE")).containsEntry("state", "COMPLETED");
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "RUNNING");
    }

    @Test
    void returningAWaitingTicketToTheQueueResumesItsClock() {
        String id = waitingIncident();

        MvcTestResult unassigned = mvc.put()
                .uri("/api/tickets/{id}/assignment", id)
                .header(HttpHeaders.AUTHORIZATION, agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"%s\", \"assigneeId\": null, \"version\": 2}".formatted(team))
                .exchange();

        assertThat(unassigned).hasStatusOk();
        assertThat(status(id)).isEqualTo("NEW");
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "RUNNING");
    }

    @Test
    void cancellingATicketCancelsItsOpenClocks() {
        String id = createIncident();

        assertThat(move(requesterToken, id, 0, "CANCELLED", "{\"reason\": \"Raised by mistake\"}"))
                .hasStatusOk();

        assertThat(clock(id, "RESPONSE")).containsEntry("state", "CANCELLED");
        assertThat(clock(id, "RESOLUTION")).containsEntry("state", "CANCELLED");
    }

    @Test
    void theBreachJobRecordsEachOverdueClockOnceAsTheSystem() {
        String id = createIncident();
        advance(Duration.ofHours(2)); // past the 1 h response target, within the 8 h resolution

        assertThat(breaches.recordOverdueBatch(clock.instant())).isEqualTo(1);
        assertThat(breaches.recordOverdueBatch(clock.instant())).isZero();

        Map<String, Object> response = clock(id, "RESPONSE");
        assertThat(response.get("breached_at")).isEqualTo(response.get("due_at"));
        assertThat(response).containsEntry("state", "RUNNING");
        assertThat(clock(id, "RESOLUTION").get("breached_at")).isNull();

        List<Map<String, Object>> audits = breachAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).containsEntry("metric", "RESPONSE").containsEntry("actor_id", null);
    }

    @Test
    void aBreachFoundDuringAnAgentsChangeIsStillRecordedAsTheSystem() {
        String id = assignedIncident();
        advance(Duration.ofHours(2));

        assertThat(move(agentToken, id, 0, "IN_PROGRESS", null)).hasStatusOk(); // responds late

        assertThat(clock(id, "RESPONSE")).containsEntry("state", "COMPLETED");
        assertThat(clock(id, "RESPONSE").get("breached_at")).isNotNull();
        assertThat(breachAudits())
                .singleElement()
                .satisfies(audit ->
                        assertThat(audit).containsEntry("metric", "RESPONSE").containsEntry("actor_id", null));
    }

    @Test
    void ticketsFromBeforeTheSlaEngineStayWithoutClocks() {
        String id = assignedIncident();
        jdbc.update("delete from sla_clocks where work_item_id = ?::uuid", id);

        assertThat(move(agentToken, id, 0, "IN_PROGRESS", null)).hasStatusOk();

        assertThat(jdbc.queryForObject(
                        "select count(*) from sla_clocks where work_item_id = ?::uuid", Integer.class, id))
                .isZero();
    }

    private String createIncident() {
        return created("/api/incidents", """
                {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s",
                 "impact": "MEDIUM", "urgency": "HIGH"}
                """.formatted(incidentCategory));
    }

    private String assignedIncident() {
        String id = createIncident();
        jdbc.update("update work_items set status = 'ASSIGNED', assignee_id = ? where id = ?::uuid", agent.getId(), id);
        return id;
    }

    /** Assigned, in progress, then waiting for the requester (version 2). */
    private String waitingIncident() {
        String id = assignedIncident();
        assertThat(move(agentToken, id, 0, "IN_PROGRESS", null)).hasStatusOk();
        assertThat(move(agentToken, id, 1, "WAITING_FOR_USER", "{\"reason\": \"Which floor?\"}"))
                .hasStatusOk();
        return id;
    }

    private String created(String path, String json) {
        MvcTestResult result = mvc.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, requesterToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code extraJson} is merged into the request body, e.g. {"reason": "..."}. */
    private MvcTestResult move(String token, String id, long version, String target, String extraJson) {
        String extras = extraJson == null
                ? ""
                : ", " + extraJson.strip().substring(1, extraJson.strip().length() - 1);
        return mvc.post()
                .uri("/api/tickets/{id}/transitions", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\": \"%s\", \"version\": %d%s}".formatted(target, version, extras))
                .exchange();
    }

    private MvcTestResult comment(String token, String id, String visibility, String body) {
        return mvc.post()
                .uri("/api/tickets/{id}/comments", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visibility\": \"%s\", \"body\": \"%s\"}".formatted(visibility, body))
                .exchange();
    }

    private Map<String, Object> clock(String ticketId, String metric) {
        return jdbc.queryForMap(
                "select state, target_minutes, elapsed_ms, due_at, breached_at from sla_clocks"
                        + " where work_item_id = ?::uuid and metric = ?",
                ticketId,
                metric);
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from work_items where id = ?::uuid", String.class, id);
    }

    private List<Map<String, Object>> breachAudits() {
        return jdbc.queryForList(
                "select actor_id, metadata->>'metric' as metric from audit_events where action = 'SLA_BREACHED'");
    }
}
