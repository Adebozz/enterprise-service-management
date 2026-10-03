package com.ademola.esm.ticket.history;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.jayway.jsonpath.JsonPath;
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

@IntegrationTest
class TicketHistoryApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    UUID network;
    UUID networkCategory;
    User agent;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        network = fixtures.team("Network Team");
        UUID hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        users.create("requester@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        User hw = users.create("hw@example.com", Role.AGENT);
        fixtures.member(network, agent.getId());
        fixtures.member(hardware, hw.getId());
    }

    @Test
    void timelineTellsTheTicketsStoryWithActorNamesAndStructuredValues() {
        String requester = loginOk(mvc, "requester@example.com").bearer();
        String agentToken = loginOk(mvc, "agent@example.com").bearer();
        String id = createIncident(requester);
        send(
                mvc.put().uri("/api/tickets/{id}/assignment", id),
                agentToken,
                "{\"teamId\": \"%s\", \"assigneeId\": \"%s\", \"version\": 0}".formatted(network, agent.getId()));
        send(
                mvc.post().uri("/api/tickets/{id}/transitions", id),
                agentToken,
                "{\"targetStatus\": \"IN_PROGRESS\", \"version\": 1}");
        send(
                mvc.post().uri("/api/tickets/{id}/comments", id),
                agentToken,
                "{\"visibility\": \"INTERNAL\", \"body\": \"Switch port 12 flapping\"}");

        MvcTestResult history = mvc.get()
                .uri("/api/tickets/{id}/history", id)
                .header(HttpHeaders.AUTHORIZATION, agentToken)
                .exchange();

        assertThat(history).hasStatusOk();
        assertThat(history)
                .bodyJson()
                .extractingPath("$[*].action")
                .asArray()
                .containsExactly(
                        "TICKET_CREATED", "TICKET_ASSIGNMENT_CHANGED", "TICKET_STATUS_CHANGED", "COMMENT_ADDED");
        assertThat(history).bodyJson().extractingPath("$[0].actor.name").isEqualTo("Requester");
        assertThat(history).bodyJson().extractingPath("$[1].oldValue.status").isEqualTo("NEW");
        assertThat(history).bodyJson().extractingPath("$[1].newValue.status").isEqualTo("ASSIGNED");
        assertThat(history).bodyJson().extractingPath("$[2].actor.name").isEqualTo("Agent");
        assertThat(history)
                .bodyJson()
                .extractingPath("$[3].metadata.visibility")
                .isEqualTo("INTERNAL");
    }

    @Test
    void requestersAndUninvolvedStaffCannotReadTheHistory() {
        String requester = loginOk(mvc, "requester@example.com").bearer();
        String id = createIncident(requester);

        MvcTestResult byRequester = mvc.get()
                .uri("/api/tickets/{id}/history", id)
                .header(HttpHeaders.AUTHORIZATION, requester)
                .exchange();
        assertThat(byRequester).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(byRequester).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");

        assertThat(mvc.get()
                        .uri("/api/tickets/{id}/history", id)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                loginOk(mvc, "hw@example.com").bearer()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private String createIncident(String token) {
        MvcTestResult result = send(mvc.post().uri("/api/incidents"), token, """
                {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s", "impact": "LOW", "urgency": "LOW"}
                """.formatted(networkCategory));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request, String token, String json) {
        MvcTestResult result = request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
        assertThat(result.getResponse().getStatus()).as(json).isLessThan(300);
        return result;
    }
}
