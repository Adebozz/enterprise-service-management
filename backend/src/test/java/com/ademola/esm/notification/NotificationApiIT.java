package com.ademola.esm.notification;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.jayway.jsonpath.JsonPath;
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

/** Notifications created from real changes, the inbox API, and the email outbox. */
@IntegrationTest
class NotificationApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    @Autowired
    NotificationEmailService emails;

    @Autowired
    RecordingMailGateway mail;

    UUID network;
    UUID networkCategory;
    User requester;
    User agent;

    /** The last raised ticket's reference (sequences aren't reset between tests). */
    String ref;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        mail.reset();
        network = fixtures.team("Network Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        requester = users.create("requester@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        User lead = users.create("lead@example.com", Role.TEAM_LEAD);
        fixtures.member(network, agent.getId());
        fixtures.member(network, lead.getId());
    }

    @Test
    void aTicketsLifeTellsEachPersonWhatTheyNeedToKnowOnce() {
        String id = incidentRaisedBy("requester@example.com");
        assign("lead@example.com", id, agent.getId(), 0);
        comment("agent@example.com", id, "INTERNAL", "Checking the access point");
        comment("agent@example.com", id, "PUBLIC", "On it");
        transition("agent@example.com", id, 1, "IN_PROGRESS", "");
        transition("agent@example.com", id, 2, "WAITING_FOR_USER", ", \"reason\": \"Which floor?\"");
        comment("requester@example.com", id, "PUBLIC", "Floor 3");

        assertThat(messagesOf("agent@example.com"))
                .containsExactly(
                        "Requester replied on %s: Wi-Fi down".formatted(ref),
                        "Lead assigned you %s: Wi-Fi down".formatted(ref));
        // Never the internal note, never the status-change reason as a second notification.
        assertThat(messagesOf("requester@example.com"))
                .containsExactly(
                        "Agent moved %s to waiting for user: Wi-Fi down".formatted(ref),
                        "Agent moved %s to in progress: Wi-Fi down".formatted(ref),
                        "Agent replied on %s: Wi-Fi down".formatted(ref));
        assertThat(messagesOf("lead@example.com")).isEmpty();
    }

    @Test
    void theInboxCountsUnreadAndOnlyItsOwnerCanMarkThem() {
        String id = incidentRaisedBy("requester@example.com");
        assign("lead@example.com", id, agent.getId(), 0);
        comment("agent@example.com", id, "PUBLIC", "First");
        comment("agent@example.com", id, "PUBLIC", "Second");

        MvcTestResult inbox = get("requester@example.com", "/api/notifications");
        assertThat(inbox).bodyJson().extractingPath("$.unreadCount").isEqualTo(2);
        assertThat(inbox).bodyJson().extractingPath("$.items[0].ticketId").isEqualTo(id);
        assertThat(inbox).bodyJson().extractingPath("$.items[0].kind").isEqualTo("COMMENTED");
        String first = JsonPath.read(body(inbox), "$.items[0].id");

        assertThat(post("agent@example.com", "/api/notifications/" + first + "/read"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post("requester@example.com", "/api/notifications/" + first + "/read"))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get("requester@example.com", "/api/notifications/unread-count"))
                .bodyJson()
                .extractingPath("$.count")
                .isEqualTo(1);
        assertThat(get("requester@example.com", "/api/notifications"))
                .bodyJson()
                .extractingPath("$.items[*].read")
                .asArray()
                .containsExactly(true, false);

        assertThat(post("requester@example.com", "/api/notifications/read-all")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get("requester@example.com", "/api/notifications/unread-count"))
                .bodyJson()
                .extractingPath("$.count")
                .isEqualTo(0);
        assertThat(get("agent@example.com", "/api/notifications/unread-count"))
                .bodyJson()
                .extractingPath("$.count")
                .as("someone else's read-all changes nothing of yours")
                .isEqualTo(1);
    }

    @Test
    void withEmailOffNotificationsAreKeptOutOfTheOutbox() {
        String id = incidentRaisedBy("requester@example.com");
        assign("lead@example.com", id, agent.getId(), 0);

        assertThat(jdbc.queryForList("select email_status from notifications", String.class))
                .containsOnly("SKIPPED");
        assertThat(emails.sendPendingBatch()).isZero();
    }

    @Test
    void theOutboxEmailsPendingNotificationsWithALinkToTheTicket() {
        String id = incidentRaisedBy("requester@example.com");
        assign("lead@example.com", id, agent.getId(), 0);
        jdbc.update("update notifications set email_status = 'PENDING'");

        assertThat(emails.sendPendingBatch()).isEqualTo(1);

        assertThat(mail.sent()).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo("agent@example.com");
            assertThat(email.subject()).isEqualTo("Lead assigned you %s: Wi-Fi down".formatted(ref));
            assertThat(email.body()).contains("Hello Agent,").contains("/tickets/" + id);
        });
        assertThat(jdbc.queryForObject("select email_status from notifications", String.class))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select emailed_at is not null from notifications", Boolean.class))
                .isTrue();
        assertThat(emails.sendPendingBatch()).as("nothing is sent twice").isZero();
    }

    @Test
    void aBrokenMailServerIsRetriedThenGivenUpOnWithoutTouchingTheTicket() {
        String id = incidentRaisedBy("requester@example.com");
        assign("lead@example.com", id, agent.getId(), 0);
        jdbc.update("update notifications set email_status = 'PENDING'");
        mail.failing(true);

        for (int attempt = 1; attempt <= NotificationEmailService.MAX_ATTEMPTS; attempt++) {
            assertThat(emails.sendPendingBatch()).isEqualTo(1);
        }

        assertThat(jdbc.queryForMap("select email_status, email_attempts from notifications"))
                .containsEntry("email_status", "FAILED")
                .containsEntry("email_attempts", NotificationEmailService.MAX_ATTEMPTS);
        assertThat(emails.sendPendingBatch()).isZero();
        assertThat(get("agent@example.com", "/api/notifications/unread-count"))
                .bodyJson()
                .extractingPath("$.count")
                .isEqualTo(1);
    }

    @Test
    void deactivatedPeopleGetNoEmail() {
        String id = incidentRaisedBy("requester@example.com");
        assign("lead@example.com", id, agent.getId(), 0);
        jdbc.update("update notifications set email_status = 'PENDING'");
        jdbc.update("update users set active = false where id = ?", agent.getId());

        emails.sendPendingBatch();

        assertThat(mail.sent()).isEmpty();
        assertThat(jdbc.queryForObject("select email_status from notifications", String.class))
                .isEqualTo("SKIPPED");
    }

    // ----- helpers ------------------------------------------------------------------------------

    private List<String> messagesOf(String email) {
        return JsonPath.read(body(get(email, "/api/notifications")), "$.items[*].message");
    }

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
        ref = JsonPath.read(body(result), "$.reference");
        return JsonPath.read(body(result), "$.id");
    }

    private void assign(String email, String id, UUID assignee, long version) {
        assertThat(mvc.put()
                        .uri("/api/tickets/{id}/assignment", id)
                        .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": \"%s\", \"assigneeId\": \"%s\", \"version\": %d}"
                                .formatted(network, assignee, version)))
                .hasStatusOk();
    }

    private void comment(String email, String id, String visibility, String text) {
        assertThat(mvc.post()
                        .uri("/api/tickets/{id}/comments", id)
                        .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\": \"%s\", \"body\": \"%s\"}".formatted(visibility, text)))
                .hasStatus(HttpStatus.CREATED);
    }

    private void transition(String email, String id, long version, String target, String extra) {
        assertThat(mvc.post()
                        .uri("/api/tickets/{id}/transitions", id)
                        .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\": \"%s\", \"version\": %d%s}".formatted(target, version, extra)))
                .hasStatusOk();
    }

    private MvcTestResult get(String email, String path) {
        return mvc.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private MvcTestResult post(String email, String path) {
        return mvc.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
