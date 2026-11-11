package com.ademola.esm.approval;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
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

/** Approvals: requests held until a team lead or admin signs them off, end to end through the API. */
@IntegrationTest
class ApprovalApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    UUID identity;
    UUID guardedItem;
    UUID openItem;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        identity = fixtures.team("Identity & Access");
        UUID network = fixtures.team("Network Team");
        UUID access = fixtures.category("ACCESS", "Access", null, identity, "SERVICE_REQUEST");
        guardedItem = item("Shared drive access", access, true);
        openItem = item("Mouse", access, false);

        users.create("requester@example.com", Role.REQUESTER);
        User agent = users.create("agent@example.com", Role.AGENT);
        User lead = users.create("lead@example.com", Role.TEAM_LEAD);
        User otherLead = users.create("otherlead@example.com", Role.TEAM_LEAD);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(identity, agent.getId());
        fixtures.member(identity, lead.getId());
        fixtures.member(network, otherLead.getId());
    }

    @Test
    void anItemThatNeedsApprovalHoldsTheRequestWithItsClocksPaused() {
        String ticket = order("requester@example.com", guardedItem);

        assertThat(get("requester@example.com", "/api/tickets/" + ticket))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("APPROVAL_PENDING");
        assertThat(jdbc.queryForList("select state from sla_clocks where work_item_id = ?::uuid", String.class, ticket))
                .containsOnly("PAUSED");

        MvcTestResult approval = get("requester@example.com", "/api/tickets/" + ticket + "/approval");
        assertThat(approval).hasStatusOk();
        assertThat(approval).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(approval).bodyJson().extractingPath("$.canDecide").isEqualTo(false);
        // Support can't start work around the approval: no move is offered except cancelling.
        assertThat(get("agent@example.com", "/api/tickets/" + ticket + "/transitions"))
                .bodyJson()
                .extractingPath("$[*].targetStatus")
                .isEqualTo(List.of("CANCELLED"));
    }

    @Test
    void theInboxShowsOnlyWhatTheCallerMayDecide() {
        String ticket = order("requester@example.com", guardedItem);

        MvcTestResult lead = get("lead@example.com", "/api/approvals");
        assertThat(lead).bodyJson().extractingPath("$[0].ticketId").isEqualTo(ticket);
        assertThat(lead).bodyJson().extractingPath("$[0].title").isEqualTo("Shared drive access");
        assertThat(lead).bodyJson().extractingPath("$[0].requester.name").isNotNull();
        assertThat(lead).bodyJson().extractingPath("$[0].team.name").isEqualTo("Identity & Access");
        assertThat(get("admin@example.com", "/api/approvals"))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(1);
        assertThat(get("otherlead@example.com", "/api/approvals")).bodyJson().isEqualTo("[]");
        assertThat(get("agent@example.com", "/api/approvals")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void approvingReleasesTheRequestToSupport() {
        String ticket = order("requester@example.com", guardedItem);
        String approval = approvalId(ticket);

        MvcTestResult decided = decide("lead@example.com", approval, "APPROVE", "Fine for a quarter", 0);

        assertThat(decided).hasStatusOk();
        assertThat(decided).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
        assertThat(decided).bodyJson().extractingPath("$.decidedBy.name").isNotNull();
        assertThat(decided).bodyJson().extractingPath("$.canDecide").isEqualTo(false);
        assertThat(get("requester@example.com", "/api/tickets/" + ticket))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("select state from sla_clocks where work_item_id = ?::uuid", String.class, ticket))
                .containsOnly("RUNNING");
        // The approver's comment reaches the requester in the conversation.
        assertThat(get("requester@example.com", "/api/tickets/" + ticket + "/comments"))
                .bodyJson()
                .extractingPath("$[0].body")
                .isEqualTo("Fine for a quarter");
        assertThat(jdbc.queryForObject(
                        "select metadata->>'trigger' from audit_events where action = 'TICKET_STATUS_CHANGED'"
                                + " and new_value->>'status' = 'APPROVED'",
                        String.class))
                .isEqualTo("APPROVAL");
        assertThat(get("lead@example.com", "/api/approvals")).bodyJson().isEqualTo("[]");
    }

    @Test
    void rejectingNeedsAReasonAndEndsTheRequest() {
        String ticket = order("requester@example.com", guardedItem);
        String approval = approvalId(ticket);

        MvcTestResult unexplained = decide("lead@example.com", approval, "REJECT", null, 0);
        assertThat(unexplained).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unexplained).bodyJson().extractingPath("$.code").isEqualTo("APPROVAL_REASON_REQUIRED");

        MvcTestResult rejected = decide("admin@example.com", approval, "REJECT", "Use the read-only copy", 0);
        assertThat(rejected).hasStatusOk();
        assertThat(rejected).bodyJson().extractingPath("$.comment").isEqualTo("Use the read-only copy");
        MvcTestResult view = get("requester@example.com", "/api/tickets/" + ticket);
        assertThat(view).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(view).bodyJson().extractingPath("$.closedAt").isNotNull();
        assertThat(jdbc.queryForList("select state from sla_clocks where work_item_id = ?::uuid", String.class, ticket))
                .containsOnly("CANCELLED");
    }

    @Test
    void onlyTheRightPeopleCanDecide() {
        String ticket = order("requester@example.com", guardedItem);
        String approval = approvalId(ticket);

        MvcTestResult agent = decide("agent@example.com", approval, "APPROVE", null, 0);
        assertThat(agent).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(agent).bodyJson().extractingPath("$.code").isEqualTo("APPROVAL_NOT_PERMITTED");
        // A lead of another team can't even see the request.
        assertThat(decide("otherlead@example.com", approval, "APPROVE", null, 0))
                .hasStatus(HttpStatus.NOT_FOUND);
        // Nobody approves their own request, not even a team lead of the right team.
        String own = order("lead@example.com", guardedItem);
        MvcTestResult self = decide("lead@example.com", approvalId(own), "APPROVE", null, 0);
        assertThat(self).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(self).bodyJson().extractingPath("$.detail").isEqualTo("You can't approve your own request");
    }

    @Test
    void theTransitionsEndpointCannotBeUsedToApprove() {
        String ticket = order("requester@example.com", guardedItem);

        MvcTestResult result = post(
                "lead@example.com",
                "/api/tickets/" + ticket + "/transitions",
                "{\"targetStatus\": \"APPROVED\", \"version\": 1}");

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TRANSITION_NOT_PERMITTED");
    }

    @Test
    void aDecisionIsFinalAndNeedsTheCurrentVersion() {
        String ticket = order("requester@example.com", guardedItem);
        String approval = approvalId(ticket);

        assertThat(decide("lead@example.com", approval, "APPROVE", null, 3))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(decide("lead@example.com", approval, "APPROVE", null, 0)).hasStatusOk();

        MvcTestResult again = decide("admin@example.com", approval, "REJECT", "Too late", 1);
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("APPROVAL_ALREADY_DECIDED");
    }

    @Test
    void cancellingAWaitingRequestWithdrawsItsApproval() {
        String ticket = order("requester@example.com", guardedItem);
        String approval = approvalId(ticket);

        assertThat(post(
                        "requester@example.com",
                        "/api/tickets/" + ticket + "/transitions",
                        "{\"targetStatus\": \"CANCELLED\", \"version\": 1, \"reason\": \"No longer needed\"}"))
                .hasStatusOk();

        assertThat(get("requester@example.com", "/api/tickets/" + ticket + "/approval"))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("WITHDRAWN");
        assertThat(get("lead@example.com", "/api/approvals")).bodyJson().isEqualTo("[]");
        assertThat(decide("lead@example.com", approval, "APPROVE", null, 1))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("APPROVAL_ALREADY_DECIDED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'APPROVAL_WITHDRAWN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void otherRequestsGoStraightToSupport() {
        String ticket = order("requester@example.com", openItem);

        assertThat(get("requester@example.com", "/api/tickets/" + ticket))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("SUBMITTED");
        assertThat(get("requester@example.com", "/api/tickets/" + ticket + "/approval"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void approvalsOfOthersTicketsStayHidden() {
        String ticket = order("requester@example.com", guardedItem);
        users.create("stranger@example.com", Role.REQUESTER);

        assertThat(get("stranger@example.com", "/api/tickets/" + ticket + "/approval"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get("otherlead@example.com", "/api/tickets/" + ticket + "/approval"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get("lead@example.com", "/api/tickets/" + ticket + "/approval"))
                .bodyJson()
                .extractingPath("$.canDecide")
                .isEqualTo(true);
    }

    // ----- helpers ------------------------------------------------------------------------------

    private UUID item(String name, UUID category, boolean approvalRequired) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into catalogue_items (id, name, summary, category_id, approval_required, created_at, updated_at)"
                        + " values (?, ?, 'Order one', ?, ?, now(), now())",
                id,
                name,
                category,
                approvalRequired);
        return id;
    }

    private String order(String email, UUID item) {
        MvcTestResult result = post(email, "/api/catalogue/" + item + "/requests", "{}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private String approvalId(String ticket) {
        return JsonPath.read(body(get("admin@example.com", "/api/tickets/" + ticket + "/approval")), "$.id");
    }

    private MvcTestResult decide(String email, String approval, String decision, String comment, long version) {
        String json = comment == null
                ? "{\"decision\": \"%s\", \"version\": %d}".formatted(decision, version)
                : "{\"decision\": \"%s\", \"comment\": \"%s\", \"version\": %d}".formatted(decision, comment, version);
        return post(email, "/api/approvals/" + approval + "/decision", json);
    }

    private MvcTestResult get(String email, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .exchange();
    }

    private MvcTestResult post(String email, String uri, String json) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private String token(String email) {
        return loginOk(mvc, email).bearer();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
