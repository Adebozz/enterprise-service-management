package com.ademola.esm.ticket.attachment;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Uploading, listing, downloading and removing files, with the same visibility rules as comments. */
@IntegrationTest
class AttachmentApiIT {

    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 42, 42};

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
    void requesterAttachesAScreenshotThatSupportCanDownloadByteForByte() {
        String id = incidentRaisedBy("requester@example.com");

        MvcTestResult added = upload("requester@example.com", id, "wifi error.png", PNG, "PUBLIC");
        assertThat(added).hasStatus(HttpStatus.CREATED);
        assertThat(added).bodyJson().extractingPath("$.contentType").isEqualTo("image/png");
        assertThat(added).bodyJson().extractingPath("$.sizeBytes").isEqualTo(PNG.length);
        assertThat(added).bodyJson().extractingPath("$.uploadedBy.name").isEqualTo("Requester");
        assertThat(added).bodyJson().extractingPath("$.canDelete").isEqualTo(true);
        String attachmentId = JsonPath.read(body(added), "$.id");

        assertThat(list("agent@example.com", id))
                .bodyJson()
                .extractingPath("$[*].fileName")
                .asArray()
                .containsExactly("wifi error.png");
        assertThat(list("agent@example.com", id))
                .bodyJson()
                .extractingPath("$[0].canDelete")
                .isEqualTo(false);

        MvcTestResult download = download("agent@example.com", id, attachmentId);
        assertThat(download).hasStatusOk().hasContentType("image/png");
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;")
                .contains("wifi error.png");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void internalFilesAreInvisibleToTheRequesterAndToOtherTeams() {
        String id = incidentRaisedBy("requester@example.com");
        String secret = JsonPath.read(
                body(upload("agent@example.com", id, "switch-config.txt", text("enable secret"), "INTERNAL")), "$.id");
        upload("requester@example.com", id, "error.txt", text("timeout"), "PUBLIC");

        assertThat(list("requester@example.com", id))
                .bodyJson()
                .extractingPath("$[*].fileName")
                .asArray()
                .containsExactly("error.txt");
        assertThat(download("requester@example.com", id, secret)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(list("admin@example.com", id))
                .bodyJson()
                .extractingPath("$[*].visibility")
                .asArray()
                .containsExactly("INTERNAL", "PUBLIC");

        MvcTestResult attempt = upload("requester@example.com", id, "x.txt", text("x"), "INTERNAL");
        assertThat(attempt).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(list("other@example.com", id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(list("hw@example.com", id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAttachmentIsOnlyReachableThroughItsOwnTicket() {
        String mine = incidentRaisedBy("requester@example.com");
        String theirs = incidentRaisedBy("other@example.com");
        String theirFile =
                JsonPath.read(body(upload("other@example.com", theirs, "a.txt", text("private"), "PUBLIC")), "$.id");

        assertThat(download("requester@example.com", mine, theirFile)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete("requester@example.com", mine, theirFile)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void disguisedOrDisallowedFilesAreRefusedWithTheirOwnCodes() {
        String id = incidentRaisedBy("requester@example.com");

        MvcTestResult html = upload("requester@example.com", id, "page.html", text("<h1>hi</h1>"), "PUBLIC");
        assertThat(html).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(html).bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_TYPE_NOT_ALLOWED");

        MvcTestResult disguised = upload("requester@example.com", id, "photo.png", text("<script>"), "PUBLIC");
        assertThat(disguised).bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_TYPE_NOT_ALLOWED");

        MvcTestResult empty = upload("requester@example.com", id, "a.txt", new byte[0], "PUBLIC");
        assertThat(empty).bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_EMPTY");

        assertThat(jdbc.queryForObject("select count(*) from attachments", Long.class))
                .isZero();
    }

    @Test
    void filesOverTheUploadLimitAreRefusedBeforeReachingTheService() {
        String id = incidentRaisedBy("requester@example.com");
        byte[] tooBig = new byte[UploadedFile.MAX_BYTES + 1];

        MvcTestResult result = upload("requester@example.com", id, "big.txt", tooBig, "PUBLIC");
        assertThat(result).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_TOO_LARGE");
    }

    @Test
    void aTicketHoldsAtMostTwentyFiles() {
        String id = incidentRaisedBy("requester@example.com");
        for (int i = 0; i < AttachmentPolicy.MAX_PER_TICKET; i++) {
            assertThat(upload("requester@example.com", id, "f" + i + ".txt", text("n" + i), "PUBLIC"))
                    .hasStatus(HttpStatus.CREATED);
        }
        MvcTestResult extra = upload("requester@example.com", id, "one-more.txt", text("x"), "PUBLIC");
        assertThat(extra).hasStatus(HttpStatus.CONFLICT);
        assertThat(extra).bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_LIMIT_REACHED");
    }

    @Test
    void onlyTheUploaderOrAnAdminRemovesAFileAndTheRemovalIsAudited() {
        String id = incidentRaisedBy("requester@example.com");
        String first = JsonPath.read(body(upload("requester@example.com", id, "a.txt", text("a"), "PUBLIC")), "$.id");
        String second = JsonPath.read(body(upload("requester@example.com", id, "b.txt", text("b"), "PUBLIC")), "$.id");

        MvcTestResult notTheirs = delete("agent@example.com", id, first);
        assertThat(notTheirs).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(notTheirs).bodyJson().extractingPath("$.code").isEqualTo("ATTACHMENT_DELETE_NOT_PERMITTED");

        assertThat(delete("requester@example.com", id, first)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete("admin@example.com", id, second)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(list("requester@example.com", id))
                .bodyJson()
                .extractingPath("$")
                .asArray()
                .isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from attachment_contents", Long.class))
                .isZero();
        assertThat(jdbc.queryForList(
                        "select action from audit_events where action like 'ATTACHMENT%' order by occurred_at, id",
                        String.class))
                .containsExactly("ATTACHMENT_ADDED", "ATTACHMENT_ADDED", "ATTACHMENT_REMOVED", "ATTACHMENT_REMOVED");
        assertThat(jdbc.queryForObject(
                        "select metadata->>'fileName' from audit_events where action = 'ATTACHMENT_REMOVED' limit 1",
                        String.class))
                .isIn("a.txt", "b.txt");
    }

    @Test
    void aClosedTicketKeepsItsFilesButTakesNoMoreAndLosesNone() {
        String id = incidentRaisedBy("requester@example.com");
        String file = JsonPath.read(body(upload("requester@example.com", id, "a.txt", text("a"), "PUBLIC")), "$.id");
        cancel(id);

        assertThat(list("requester@example.com", id))
                .bodyJson()
                .extractingPath("$[0].canDelete")
                .isEqualTo(false);
        MvcTestResult late = upload("requester@example.com", id, "b.txt", text("b"), "PUBLIC");
        assertThat(late).hasStatus(HttpStatus.CONFLICT);
        assertThat(late).bodyJson().extractingPath("$.code").isEqualTo("TICKET_CLOSED");
        assertThat(delete("admin@example.com", id, file))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("TICKET_CLOSED");
        assertThat(download("requester@example.com", id, file)).hasStatusOk();
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

    private void cancel(String id) {
        assertThat(mvc.post()
                        .uri("/api/tickets/{id}/transitions", id)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                loginOk(mvc, "requester@example.com").bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\": \"CANCELLED\", \"version\": 0, \"reason\": \"Fixed itself\"}"))
                .hasStatusOk();
    }

    private MvcTestResult upload(String email, String id, String name, byte[] content, String visibility) {
        return mvc.post()
                .uri("/api/tickets/{id}/attachments", id)
                .multipart()
                .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .param("visibility", visibility)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private MvcTestResult list(String email, String id) {
        return mvc.get()
                .uri("/api/tickets/{id}/attachments", id)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private MvcTestResult download(String email, String id, String attachmentId) {
        return mvc.get()
                .uri("/api/tickets/{id}/attachments/{a}/content", id, attachmentId)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private MvcTestResult delete(String email, String id, String attachmentId) {
        return mvc.delete()
                .uri("/api/tickets/{id}/attachments/{a}", id, attachmentId)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
