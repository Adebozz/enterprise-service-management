package com.ademola.esm.ticket.transition;

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
import java.time.Duration;
import java.util.List;
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

/** The workflow through the HTTP API with real tokens: lifecycle, permissions, errors, audit. */
@IntegrationTest
class TicketTransitionApiIT {

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

    UUID networkTeam;
    UUID networkCategory;
    UUID accessCategory;
    User agent;
    String requesterToken;
    String agentToken;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        networkTeam = fixtures.team("Network Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, networkTeam, "INCIDENT");
        accessCategory = fixtures.category("ACCESS", "Access", null, networkTeam, "SERVICE_REQUEST");
        users.create("requester@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        users.create("outsider@example.com", Role.AGENT); // not in the network team
        users.create("other@example.com", Role.REQUESTER);
        fixtures.member(networkTeam, agent.getId());
        requesterToken = loginOk(mvc, "requester@example.com").bearer();
        agentToken = loginOk(mvc, "agent@example.com").bearer();
    }

    @Test
    void fullIncidentLifecycleWithTimestampsAndAudit() {
        String id = assignedIncident();

        clock.advance(Duration.ofMinutes(10));
        assertThat(move(agentToken, id, 0, "IN_PROGRESS", null)).hasStatusOk();
        assertThat(move(agentToken, id, 1, "WAITING_FOR_USER", "{\"reason\": \"Which floor are you on?\"}"))
                .hasStatusOk();
        assertThat(move(requesterToken, id, 2, "IN_PROGRESS", null)).hasStatusOk(); // requester replies
        MvcTestResult resolved = move(agentToken, id, 3, "RESOLVED", """
                {"resolutionCode": "FIXED", "notes": "Rebooted the floor 3 access point"}
                """);
        assertThat(resolved).hasStatusOk();
        assertThat(resolved)
                .bodyJson()
                .extractingPath("$.incident.resolutionCode")
                .isEqualTo("FIXED");
        assertThat(resolved).bodyJson().extractingPath("$.resolvedAt").isNotNull();

        MvcTestResult closed = move(requesterToken, id, 4, "CLOSED", null);
        assertThat(closed).hasStatusOk();
        assertThat(closed).bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
        assertThat(closed).bodyJson().extractingPath("$.closedAt").isNotNull();
        assertThat(closed).bodyJson().extractingPath("$.version").isEqualTo(5);

        List<String> history = jdbc.queryForList(
                "select new_value->>'status' from audit_events where action = 'TICKET_STATUS_CHANGED'"
                        + " and entity_id = ?::uuid order by occurred_at, id",
                String.class,
                id);
        assertThat(history).containsExactly("IN_PROGRESS", "WAITING_FOR_USER", "IN_PROGRESS", "RESOLVED", "CLOSED");
        assertThat(jdbc.queryForObject(
                        "select metadata->>'reason' from audit_events where new_value->>'status' = 'WAITING_FOR_USER'",
                        String.class))
                .isEqualTo("Which floor are you on?");
    }

    @Test
    void closedTicketCannotBeReopenedAndTheErrorExplainsWhy() {
        String id = assignedIncident();
        jdbc.update("update work_items set status = 'CLOSED' where id = ?::uuid", id);

        MvcTestResult result = move(agentToken, id, 0, "IN_PROGRESS", null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATUS_TRANSITION");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("Incident cannot transition from CLOSED to IN_PROGRESS");
    }

    @Test
    void statusOfAnotherTicketTypeIsRejected() {
        String id = assignedIncident();

        MvcTestResult result = move(agentToken, id, 0, "FULFILLED", null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("'FULFILLED' is not a valid incident status");
    }

    @Test
    void requesterCannotResolveAndSupportCannotConfirmClosure() {
        String id = assignedIncident();
        move(agentToken, id, 0, "IN_PROGRESS", null);

        MvcTestResult requesterResolves = move(requesterToken, id, 1, "RESOLVED", """
                {"resolutionCode": "FIXED", "notes": "I fixed it myself"}
                """);
        assertThat(requesterResolves).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(requesterResolves).bodyJson().extractingPath("$.code").isEqualTo("TRANSITION_NOT_PERMITTED");

        move(agentToken, id, 1, "RESOLVED", "{\"resolutionCode\": \"FIXED\", \"notes\": \"done\"}");
        assertThat(move(agentToken, id, 2, "CLOSED", null)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void usersWhoCannotSeeTheTicketGet404NotForbidden() {
        String id = assignedIncident();

        assertThat(move(loginOk(mvc, "outsider@example.com").bearer(), id, 0, "IN_PROGRESS", null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(move(loginOk(mvc, "other@example.com").bearer(), id, 0, "CANCELLED", "{\"reason\": \"x\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingRequirementsAre422AndChangeNothing() {
        String id = assignedIncident();
        move(agentToken, id, 0, "IN_PROGRESS", null);

        MvcTestResult noResolution = move(agentToken, id, 1, "RESOLVED", "{\"resolutionCode\": \"FIXED\"}");
        MvcTestResult noReason = move(agentToken, id, 1, "WAITING_FOR_USER", null);

        assertThat(noResolution).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noResolution).bodyJson().extractingPath("$.code").isEqualTo("TRANSITION_REQUIREMENT_MISSING");
        assertThat(noReason).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'TICKET_STATUS_CHANGED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void unknownResolutionCodeIsRejectedByTheContract() {
        String id = assignedIncident();
        move(agentToken, id, 0, "IN_PROGRESS", null);

        MvcTestResult result = move(agentToken, id, 1, "RESOLVED", "{\"resolutionCode\": \"MAGIC\", \"notes\": \"x\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void staleVersionIsAConflict() {
        String id = assignedIncident();
        move(agentToken, id, 0, "IN_PROGRESS", null);

        MvcTestResult stale = move(agentToken, id, 0, "WAITING_FOR_USER", "{\"reason\": \"x\"}");

        assertThat(stale).hasStatus(HttpStatus.CONFLICT);
        assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void reopeningCountsAndClearsTheResolution() {
        String id = assignedIncident();
        move(agentToken, id, 0, "IN_PROGRESS", null);
        move(agentToken, id, 1, "RESOLVED", "{\"resolutionCode\": \"WORKAROUND\", \"notes\": \"Use cable\"}");

        MvcTestResult reopened = move(requesterToken, id, 2, "IN_PROGRESS", "{\"reason\": \"Still broken\"}");

        assertThat(reopened).hasStatusOk();
        assertThat(reopened).bodyJson().extractingPath("$.incident.reopenCount").isEqualTo(1);
        assertThat(reopened)
                .bodyJson()
                .extractingPath("$.incident.resolutionCode")
                .isNull();
        assertThat(reopened).bodyJson().extractingPath("$.resolvedAt").isNull();
    }

    @Test
    void availableTransitionsDependOnWhoIsAsking() {
        String id = assignedIncident();
        move(agentToken, id, 0, "IN_PROGRESS", null);
        move(agentToken, id, 1, "RESOLVED", "{\"resolutionCode\": \"FIXED\", \"notes\": \"done\"}");

        assertThat(available(requesterToken, id))
                .bodyJson()
                .extractingPath("$[*].targetStatus")
                .asArray()
                .containsExactlyInAnyOrder("IN_PROGRESS", "CLOSED");
        assertThat(available(agentToken, id))
                .bodyJson()
                .extractingPath("$[*].targetStatus")
                .asArray()
                .containsExactly("IN_PROGRESS");
        assertThat(available(agentToken, id))
                .bodyJson()
                .extractingPath("$[0].label")
                .isEqualTo("Reopen");
        assertThat(available(agentToken, id))
                .bodyJson()
                .extractingPath("$[0].requirements")
                .asArray()
                .containsExactly("REASON");
    }

    @Test
    void unassignedTicketOffersNoStartButtonAndCannotBeStarted() {
        String id = created("/api/service-requests", """
                {"title": "VPN", "description": "Need VPN", "categoryId": "%s"}
                """.formatted(accessCategory));

        assertThat(available(agentToken, id))
                .bodyJson()
                .extractingPath("$[*].targetStatus")
                .asArray()
                .containsExactly("CANCELLED");
        MvcTestResult start = move(agentToken, id, 0, "IN_PROGRESS", null);
        assertThat(start).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(start).bodyJson().extractingPath("$.code").isEqualTo("TICKET_NOT_ASSIGNED");
    }

    @Test
    void approvalCannotBeForcedThroughTheApi() {
        String id = created("/api/service-requests", """
                {"title": "VPN", "description": "Need VPN", "categoryId": "%s"}
                """.formatted(accessCategory));
        jdbc.update("update work_items set status = 'APPROVAL_PENDING' where id = ?::uuid", id);

        assertThat(move(loginOk(mvc, "requester@example.com").bearer(), id, 0, "APPROVED", null))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(move(agentToken, id, 0, "APPROVED", null)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void serviceRequestFulfilmentRequiresNotes() {
        String id = created("/api/service-requests", """
                {"title": "VPN", "description": "Need VPN", "categoryId": "%s"}
                """.formatted(accessCategory));
        jdbc.update("update work_items set assignee_id = ? where id = ?::uuid", agent.getId(), id);
        move(agentToken, id, 0, "IN_PROGRESS", null);

        assertThat(move(agentToken, id, 1, "FULFILLED", null)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        MvcTestResult fulfilled = move(agentToken, id, 1, "FULFILLED", "{\"notes\": \"VPN profile installed\"}");
        assertThat(fulfilled).hasStatusOk();
        assertThat(fulfilled)
                .bodyJson()
                .extractingPath("$.serviceRequest.fulfilmentNotes")
                .isEqualTo("VPN profile installed");
    }

    // ----- helpers ------------------------------------------------------------------------------

    /** Raised by the requester, then assigned to the agent (assignment API arrives in M5). */
    private String assignedIncident() {
        String id = created("/api/incidents", """
                {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s",
                 "impact": "MEDIUM", "urgency": "HIGH"}
                """.formatted(networkCategory));
        jdbc.update("update work_items set status = 'ASSIGNED', assignee_id = ? where id = ?::uuid", agent.getId(), id);
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
        } catch (java.io.UnsupportedEncodingException e) {
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

    private MvcTestResult available(String token, String id) {
        return mvc.get()
                .uri("/api/tickets/{id}/transitions", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange();
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from work_items where id = ?::uuid", String.class, id);
    }
}
