package com.ademola.esm.ticket.comment;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Public conversation and internal notes, with the server-side visibility guarantees. */
@IntegrationTest
class CommentApiIT {

    static final String SECRET_NOTE = "Suspect the requester's laptop is compromised";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    UUID networkCategory;
    User agent;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        UUID network = fixtures.team("Network Team");
        UUID hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        users.create("requester@example.com", Role.REQUESTER);
        users.create("other@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        User hardwareAgent = users.create("hw@example.com", Role.AGENT);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(network, agent.getId());
        fixtures.member(hardware, hardwareAgent.getId());
    }

    @Test
    void requesterNeverReceivesInternalNotesNotEvenInTheRawResponse() {
        String id = incidentRaisedBy("requester@example.com");
        assertThat(comment("requester@example.com", id, "PUBLIC", "Still down this morning"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(comment("agent@example.com", id, "INTERNAL", SECRET_NOTE)).hasStatus(HttpStatus.CREATED);
        assertThat(comment("agent@example.com", id, "PUBLIC", "Looking into it now"))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult forRequester = thread("requester@example.com", id);
        assertThat(forRequester).hasStatusOk();
        assertThat(forRequester)
                .bodyJson()
                .extractingPath("$[*].body")
                .asArray()
                .containsExactly("Still down this morning", "Looking into it now");
        assertThat(forRequester).bodyText().doesNotContain(SECRET_NOTE).doesNotContain("INTERNAL");

        assertThat(thread("agent@example.com", id))
                .bodyJson()
                .extractingPath("$[*].visibility")
                .asArray()
                .containsExactly("PUBLIC", "INTERNAL", "PUBLIC");
        assertThat(thread("admin@example.com", id)).bodyText().contains(SECRET_NOTE);
    }

    @Test
    void agentWhoRaisedATicketForAnotherTeamCannotSeeOrWriteThatTeamsInternalNotes() {
        String id = incidentRaisedBy("hw@example.com"); // routed to Network, raised by a Hardware agent
        comment("agent@example.com", id, "INTERNAL", SECRET_NOTE);

        assertThat(thread("hw@example.com", id)).hasStatusOk().bodyText().doesNotContain(SECRET_NOTE);
        MvcTestResult attempt = comment("hw@example.com", id, "INTERNAL", "sneaky");
        assertThat(attempt).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(attempt).bodyJson().extractingPath("$.code").isEqualTo("COMMENT_NOT_PERMITTED");
    }

    @Test
    void requesterCannotWriteInternalNotesAndStrangersCannotSeeTheThread() {
        String id = incidentRaisedBy("requester@example.com");

        assertThat(comment("requester@example.com", id, "INTERNAL", "x")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(thread("other@example.com", id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(comment("other@example.com", id, "PUBLIC", "x")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void supportsFirstPublicReplyRecordsFirstResponseWithoutChangingTheVersion() {
        String id = incidentRaisedBy("requester@example.com");
        comment("requester@example.com", id, "PUBLIC", "Any update?");
        comment("agent@example.com", id, "INTERNAL", "Checking switch logs");
        assertThat(ticket(id)).bodyJson().extractingPath("$.firstRespondedAt").isNull();

        comment("agent@example.com", id, "PUBLIC", "On it");
        MvcTestResult afterReply = ticket(id);
        assertThat(afterReply).bodyJson().extractingPath("$.firstRespondedAt").isNotNull();
        assertThat(afterReply).bodyJson().extractingPath("$.version").isEqualTo(0);

        Object first = JsonPath.read(body(afterReply), "$.firstRespondedAt");
        comment("agent@example.com", id, "PUBLIC", "Second reply");
        assertThat(ticket(id)).bodyJson().extractingPath("$.firstRespondedAt").isEqualTo(first);
    }

    @Test
    void transitionReasonsBecomePublicCommentsTheRequesterCanRead() {
        String id = incidentRaisedBy("requester@example.com");
        jdbc.update("update work_items set status = 'ASSIGNED', assignee_id = ? where id = ?::uuid", agent.getId(), id);
        transition(id, 0, "IN_PROGRESS", "");
        transition(id, 1, "WAITING_FOR_USER", ", \"reason\": \"Which floor are you on?\"");

        MvcTestResult forRequester = thread("requester@example.com", id);
        assertThat(forRequester).bodyJson().extractingPath("$[0].body").isEqualTo("Which floor are you on?");
        assertThat(forRequester).bodyJson().extractingPath("$[0].relatedStatus").isEqualTo("WAITING_FOR_USER");
        assertThat(forRequester).bodyJson().extractingPath("$[0].author.name").isEqualTo("Agent");
    }

    @Test
    void cancellationReasonIsRecordedEvenThoughTheTicketIsNowClosedToComments() {
        String id = incidentRaisedBy("requester@example.com");
        MvcTestResult cancelled = mvc.post()
                .uri("/api/tickets/{id}/transitions", id)
                .header(
                        HttpHeaders.AUTHORIZATION,
                        loginOk(mvc, "requester@example.com").bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\": \"CANCELLED\", \"version\": 0, \"reason\": \"Fixed itself\"}")
                .exchange();
        assertThat(cancelled).hasStatusOk();

        assertThat(thread("requester@example.com", id))
                .bodyJson()
                .extractingPath("$[0].body")
                .isEqualTo("Fixed itself");
        MvcTestResult late = comment("requester@example.com", id, "PUBLIC", "Actually it broke again");
        assertThat(late).hasStatus(HttpStatus.CONFLICT);
        assertThat(late).bodyJson().extractingPath("$.code").isEqualTo("TICKET_CLOSED");
    }

    @Test
    void auditRecordsTheCommentButNotItsContent() {
        String id = incidentRaisedBy("requester@example.com");
        comment("agent@example.com", id, "INTERNAL", SECRET_NOTE);

        String metadata = jdbc.queryForObject(
                "select metadata::text from audit_events where action = 'COMMENT_ADDED'", String.class);
        assertThat(metadata).contains("INTERNAL").doesNotContain(SECRET_NOTE);
    }

    @Test
    void blankOrOversizedBodiesAreRejectedByTheApiAndTheDatabase() {
        String id = incidentRaisedBy("requester@example.com");

        assertThat(comment("requester@example.com", id, "PUBLIC", "   ")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(comment("requester@example.com", id, "PUBLIC", "x".repeat(10_001)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "insert into comments (id, work_item_id, author_id, visibility, body, created_at)"
                                + " values (?, ?::uuid, ?, 'PUBLIC', '  ', now())",
                        UUID.randomUUID(),
                        id,
                        agent.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("comments_body_check");
    }

    // ----- helpers ------------------------------------------------------------------------------

    private String incidentRaisedBy(String email) {
        MvcTestResult result = mvc.post()
                .uri("/api/incidents")
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s",
                         "impact": "LOW", "urgency": "LOW"}
                        """.formatted(networkCategory))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private MvcTestResult comment(String email, String id, String visibility, String text) {
        return mvc.post()
                .uri("/api/tickets/{id}/comments", id)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visibility\": \"%s\", \"body\": \"%s\"}".formatted(visibility, text))
                .exchange();
    }

    private MvcTestResult thread(String email, String id) {
        return mvc.get()
                .uri("/api/tickets/{id}/comments", id)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private MvcTestResult ticket(String id) {
        return mvc.get()
                .uri("/api/tickets/{id}", id)
                .header(
                        HttpHeaders.AUTHORIZATION,
                        loginOk(mvc, "admin@example.com").bearer())
                .exchange();
    }

    private void transition(String id, long version, String target, String extra) {
        assertThat(mvc.post()
                        .uri("/api/tickets/{id}/transitions", id)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                loginOk(mvc, "agent@example.com").bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\": \"%s\", \"version\": %d%s}".formatted(target, version, extra)))
                .hasStatusOk();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
