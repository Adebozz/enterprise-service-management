package com.ademola.esm.ticket.queue;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Visibility, views, filters, search, sorting and paging through the real endpoint and SQL.
 *
 * <pre>
 * ref         team      requester  assignee  status       priority  title / category
 * INC-800001  Network   alice      netagent  IN_PROGRESS  P1        "Wi-Fi drops on floor 3" / Network>Wi-Fi
 * INC-800002  Network   alice      -         NEW          P3        "Printer jammed again"   / Network
 * INC-800003  Network   bob        -         CLOSED       P4        "VPN slow"               / Network
 * INC-800004  Hardware  bob        -         NEW          P2        "Laptop screen flicker"  / Hardware
 * REQ-800005  Hardware  netagent   hwagent   IN_PROGRESS  P4        "Printers for new office"/ Hardware
 * </pre>
 */
@IntegrationTest
class TicketListApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    UUID network;
    UUID hardware;
    UUID networkCategory;
    UUID wifi;
    UUID hardwareCategory;
    User alice;
    User bob;
    User netAgent;
    User hwAgent;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        network = fixtures.team("Network Team");
        hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "ANY");
        wifi = fixtures.category("WIFI", "Wi-Fi", networkCategory, null, "ANY");
        hardwareCategory = fixtures.category("HARDWARE", "Hardware", null, hardware, "ANY");
        alice = users.create("alice@example.com", Role.REQUESTER);
        bob = users.create("bob@example.com", Role.REQUESTER);
        netAgent = users.create("netagent@example.com", Role.AGENT);
        hwAgent = users.create("hwagent@example.com", Role.AGENT);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(network, netAgent.getId());
        fixtures.member(hardware, hwAgent.getId());

        Instant base = Instant.parse("2026-10-01T10:00:00Z");
        ticket(
                "INC-800001",
                "INCIDENT",
                network,
                networkCategory,
                wifi,
                alice,
                netAgent,
                "IN_PROGRESS",
                "P1",
                "Wi-Fi drops on floor 3",
                base);
        ticket(
                "INC-800002",
                "INCIDENT",
                network,
                networkCategory,
                null,
                alice,
                null,
                "NEW",
                "P3",
                "Printer jammed again",
                base.plusSeconds(3600));
        ticket(
                "INC-800003",
                "INCIDENT",
                network,
                networkCategory,
                null,
                bob,
                null,
                "CLOSED",
                "P4",
                "VPN slow",
                base.plusSeconds(86_400));
        ticket(
                "INC-800004",
                "INCIDENT",
                hardware,
                hardwareCategory,
                null,
                bob,
                null,
                "NEW",
                "P2",
                "Laptop screen flicker",
                base.plusSeconds(2 * 86_400));
        ticket(
                "REQ-800005",
                "SERVICE_REQUEST",
                hardware,
                hardwareCategory,
                null,
                netAgent,
                hwAgent,
                "IN_PROGRESS",
                "P4",
                "Printers for new office",
                base.plusSeconds(3 * 86_400));
    }

    // ----- visibility & views -------------------------------------------------------------------

    @Test
    void requesterSeesOnlyTheirOwnTicketsNewestFirst() {
        assertRefs(list("alice@example.com", ""), "INC-800002", "INC-800001");
    }

    @Test
    void agentSeesTheirTeamsAssignedAndOwnTicketsButNotOtherTeams() {
        // Network team tickets + the request netagent raised (handled by Hardware).
        assertRefs(list("netagent@example.com", ""), "REQ-800005", "INC-800003", "INC-800002", "INC-800001");
    }

    @Test
    void adminSeesEverything() {
        assertThat(list("admin@example.com", ""))
                .bodyJson()
                .extractingPath("$.totalElements")
                .isEqualTo(5);
    }

    @Test
    void viewsNarrowWhatTheCallerCanSee() {
        assertRefs(list("netagent@example.com", "view=MINE"), "INC-800001");
        assertRefs(list("netagent@example.com", "view=REQUESTED"), "REQ-800005");
        assertRefs(list("netagent@example.com", "view=TEAM"), "INC-800003", "INC-800002", "INC-800001");
        assertRefs(list("netagent@example.com", "view=UNASSIGNED"), "INC-800002"); // INC-800003 is closed
        assertRefs(list("admin@example.com", "view=UNASSIGNED"), "INC-800004", "INC-800002");
        assertRefs(list("alice@example.com", "view=TEAM")); // requesters have no team queue
    }

    // ----- filters ------------------------------------------------------------------------------

    @Test
    void filtersCombine() {
        assertRefs(list("admin@example.com", "status=NEW,IN_PROGRESS&priority=P1,P2"), "INC-800004", "INC-800001");
        assertRefs(list("admin@example.com", "type=SERVICE_REQUEST"), "REQ-800005");
        assertRefs(list("admin@example.com", "teamId=" + hardware + "&open=true"), "REQ-800005", "INC-800004");
        assertRefs(list("admin@example.com", "assigneeId=" + hwAgent.getId()), "REQ-800005");
    }

    @Test
    void categoryFilterMatchesSubcategoriesToo() {
        assertRefs(list("admin@example.com", "categoryId=" + wifi), "INC-800001");
        assertRefs(
                list("admin@example.com", "categoryId=" + networkCategory), "INC-800003", "INC-800002", "INC-800001");
    }

    @Test
    void dateRangeIsInclusiveOfWholeDays() {
        assertRefs(
                list("admin@example.com", "createdFrom=2026-10-01&createdTo=2026-10-01"), "INC-800002", "INC-800001");
        assertRefs(list("admin@example.com", "createdFrom=2026-10-03"), "REQ-800005", "INC-800004");
    }

    // ----- search -------------------------------------------------------------------------------

    @Test
    void referenceSearchFindsOneTicketHoweverItIsTyped() {
        assertRefs(list("admin@example.com", "q=inc-800004"), "INC-800004");
    }

    @Test
    void fullTextSearchUsesEnglishStemmingAndWebSearchSyntax() {
        // Stemming: "printers" and "Printer" both become "printer"; "jamming" matches "jammed".
        assertThat(list("admin@example.com", "q=printers"))
                .bodyJson()
                .extractingPath("$.content[*].reference")
                .asArray()
                .containsExactlyInAnyOrder("INC-800002", "REQ-800005");
        assertRefs(list("admin@example.com", "q=jamming"), "INC-800002");
        assertRefs(list("admin@example.com", "q=printer -office"), "INC-800002"); // websearch syntax: exclude
    }

    @Test
    void searchMatchesRequesterAndCategoryNames() {
        assertRefs(list("admin@example.com", "q=bob&sort=reference,asc"), "INC-800003", "INC-800004");
        assertRefs(list("admin@example.com", "q=wi-fi"), "INC-800001");
    }

    @Test
    void searchNeverRevealsTicketsOutsideTheCallersVisibility() {
        assertRefs(list("hwagent@example.com", "q=wi-fi")); // Network's ticket
        assertRefs(list("alice@example.com", "q=laptop")); // Bob's ticket
    }

    @Test
    void injectionAttemptsAreJustSearchText() {
        MvcTestResult result = list("admin@example.com", "q='; DROP TABLE users; --");

        assertThat(result).hasStatusOk();
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class))
                .isEqualTo(5);
    }

    // ----- sorting & paging ---------------------------------------------------------------------

    @Test
    void workQueueSortByPriorityThenOldest() {
        assertRefs(
                list("admin@example.com", "open=true&sort=priority,asc&sort=createdAt,asc"),
                "INC-800001",
                "INC-800004",
                "INC-800002",
                "REQ-800005");
    }

    @Test
    void pagesAreStableAndCounted() {
        MvcTestResult page2 = list("admin@example.com", "size=2&page=1&sort=reference,asc");

        assertRefs(page2, "INC-800003", "INC-800004");
        assertThat(page2).bodyJson().extractingPath("$.totalElements").isEqualTo(5);
        assertThat(page2).bodyJson().extractingPath("$.totalPages").isEqualTo(3);
        assertThat(list("admin@example.com", "size=1000"))
                .bodyJson()
                .extractingPath("$.size")
                .isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sort=passwordHash",
                "status=FLYING",
                "view=EVERYTHING",
                "createdFrom=2026-10-05&createdTo=2026-10-01"
            })
    void invalidParametersAreClientErrors(String query) {
        assertThat(list("admin@example.com", query)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthenticatedCallersGet401() {
        assertThat(mvc.get().uri("/api/tickets")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ----- helpers ------------------------------------------------------------------------------

    private MvcTestResult list(String email, String query) {
        return mvc.get()
                .uri("/api/tickets?" + query)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }

    private static void assertRefs(MvcTestResult result, String... references) {
        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.content[*].reference")
                .asArray()
                .containsExactly((Object[]) references);
    }

    private void ticket(
            String reference,
            String type,
            UUID team,
            UUID category,
            UUID subcategory,
            User requester,
            User assignee,
            String status,
            String priority,
            String title,
            Instant createdAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = createdAt.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, subcategory_id, requester_id, assigned_team_id, assignee_id, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'Details', ?, 'LOW', 'LOW', ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                reference,
                type,
                title,
                status,
                priority,
                category,
                subcategory,
                requester.getId(),
                team,
                assignee == null ? null : assignee.getId(),
                at,
                at);
        jdbc.update(
                type.equals("INCIDENT")
                        ? "insert into incidents (work_item_id) values (?)"
                        : "insert into service_requests (work_item_id) values (?)",
                id);
    }
}
