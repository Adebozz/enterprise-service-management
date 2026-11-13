package com.ademola.esm.ticket.change;

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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Change management end to end: planning, approval, the schedule and its clashes, and the review. */
@IntegrationTest
class ChangeApiIT {

    static final String NINA = "nina@example.com"; // Network agent: carries out network changes
    static final String HUGO = "hugo@example.com"; // Hardware agent: another team
    static final String LEAD = "lead@example.com"; // Network team lead: approves network changes
    static final String RITA = "rita@example.com"; // requester

    static final Instant START = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);

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
    UUID hardwareCategory;
    User nina;
    User hugo;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        network = fixtures.team("Network Team");
        hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        hardwareCategory = fixtures.category("HARDWARE", "Hardware", null, hardware, "ANY");
        nina = users.create(NINA, Role.AGENT);
        hugo = users.create(HUGO, Role.AGENT);
        User lead = users.create(LEAD, Role.TEAM_LEAD);
        users.create(RITA, Role.REQUESTER);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(network, nina.getId());
        fixtures.member(network, lead.getId());
        fixtures.member(hardware, hugo.getId());
    }

    @Test
    void supportRaisesChangesThatEveryAgentCanSeeButRequestersCannot() {
        MvcTestResult created = create(NINA, networkCategory, "NORMAL", "MEDIUM", START, null);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.reference").asString().matches("CHG-\\d{6}");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        String change = id(created);
        MvcTestResult seen = get(HUGO, "/api/tickets/" + change);
        assertThat(seen).hasStatusOk();
        assertThat(seen).bodyJson().extractingPath("$.assignedTeam.name").isEqualTo("Network Team");
        assertThat(seen).bodyJson().extractingPath("$.change.changeType").isEqualTo("NORMAL");
        assertThat(seen).bodyJson().extractingPath("$.change.backoutPlan").isEqualTo("Undo it");
        assertThat(jdbc.queryForObject("select count(*) from sla_clocks", Integer.class))
                .isZero();

        assertThat(get(RITA, "/api/tickets/" + change)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(create(RITA, networkCategory, "NORMAL", "MEDIUM", START, null))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertError(
                create(NINA, networkCategory, "STANDARD", "HIGH", START, null),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "CHANGE_PLAN_INVALID");
    }

    @Test
    void onlyTheOwningTeamWritesThePlanAndOnlyBeforeSubmitting() {
        String change = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", START, null));

        assertError(plan(HUGO, change, "NORMAL", "HIGH", START), HttpStatus.FORBIDDEN, "CHANGE_UPDATE_NOT_PERMITTED");
        assertThat(plan(NINA, change, "NORMAL", "HIGH", START)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(NINA, "/api/tickets/" + change))
                .bodyJson()
                .extractingPath("$.change.risk")
                .isEqualTo("HIGH");
        assertThat(jdbc.queryForObject(
                        "select new_value ->> 'risk' from audit_events where action = 'CHANGE_PLAN_UPDATED'",
                        String.class))
                .isEqualTo("HIGH");

        assign(change);
        assertThat(transition(NINA, change, "APPROVAL_PENDING", null)).hasStatusOk();
        assertError(plan(NINA, change, "NORMAL", "LOW", START), HttpStatus.CONFLICT, "CHANGE_NOT_EDITABLE");
    }

    @Test
    void submittingOpensAnApprovalForTheTeamLead() {
        String change = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", START, null));
        assign(change);
        assertThat(available(NINA, change)).containsExactlyInAnyOrder("APPROVAL_PENDING", "CANCELLED");

        assertThat(transition(NINA, change, "APPROVAL_PENDING", null)).hasStatusOk();

        MvcTestResult inbox = get(LEAD, "/api/approvals");
        assertThat(inbox).bodyJson().extractingPath("$[0].ticketId").isEqualTo(change);
        assertThat(inbox).bodyJson().extractingPath("$[0].type").isEqualTo("CHANGE");
        // Nobody approves their own change, and approval never goes through the transitions endpoint.
        assertThat(get(LEAD, "/api/approvals"))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(1);
        assertThat(get(NINA, "/api/approvals")).hasStatus(HttpStatus.FORBIDDEN);
        assertError(transition(NINA, change, "APPROVED", null), HttpStatus.FORBIDDEN, "TRANSITION_NOT_PERMITTED");

        decide(LEAD, change, "APPROVE", null);
        assertThat(get(NINA, "/api/tickets/" + change))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("APPROVED");
    }

    @Test
    void aRejectedChangeEndsAndACancelledOneLeavesTheInbox() {
        String rejected = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", START, null));
        assign(rejected);
        transition(NINA, rejected, "APPROVAL_PENDING", null);
        decide(LEAD, rejected, "REJECT", "Not during the quarter-end freeze.");
        assertThat(get(NINA, "/api/tickets/" + rejected))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("REJECTED");

        String cancelled = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", START, null));
        assign(cancelled);
        transition(NINA, cancelled, "APPROVAL_PENDING", null);
        assertThat(transition(NINA, cancelled, "CANCELLED", "Supplier delayed the licence"))
                .hasStatusOk();
        assertThat(get(LEAD, "/api/approvals"))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "select status from approvals a where a.work_item_id = ?::uuid", String.class, cancelled))
                .isEqualTo("WITHDRAWN");
    }

    @Test
    void standardChangesAreScheduledWithoutApproval() {
        String change = id(create(HUGO, hardwareCategory, "STANDARD", "LOW", START, null));
        assignTo(change, hardware, hugo);
        assertThat(available(HUGO, change)).containsExactlyInAnyOrder("APPROVED", "CANCELLED");

        assertThat(transition(HUGO, change, "APPROVED", null)).hasStatusOk();
        assertThat(jdbc.queryForObject("select count(*) from approvals", Integer.class))
                .isZero();
    }

    @Test
    void anIncompleteOrPastPlanCannotBeSubmitted() {
        String change = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", null, null));
        assign(change);
        assertError(
                transition(NINA, change, "APPROVAL_PENDING", null),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "CHANGE_PLAN_INCOMPLETE");

        plan(NINA, change, "NORMAL", "MEDIUM", Instant.now().minus(Duration.ofHours(1)));
        assertError(
                transition(NINA, change, "APPROVAL_PENDING", null),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "CHANGE_PLAN_INVALID");

        plan(NINA, change, "EMERGENCY", "HIGH", Instant.now().minus(Duration.ofHours(1)));
        assertThat(transition(NINA, change, "APPROVAL_PENDING", null)).hasStatusOk();
    }

    @Test
    void theScheduleShowsUpcomingChangesAndEachChangeItsClashes() {
        String vpn = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", START, null));
        assign(vpn);
        transition(NINA, vpn, "APPROVAL_PENDING", null);
        String printers =
                id(create(HUGO, hardwareCategory, "STANDARD", "LOW", START.plus(Duration.ofMinutes(30)), null));
        assignTo(printers, hardware, hugo);
        transition(HUGO, printers, "APPROVED", null);
        // Later, and a draft: neither clashes with nor appears among the scheduled changes.
        String later = id(create(NINA, networkCategory, "NORMAL", "LOW", START.plus(Duration.ofDays(1)), null));
        id(create(NINA, networkCategory, "NORMAL", "LOW", START, null));

        MvcTestResult schedule = get(HUGO, "/api/changes/schedule");
        assertThat(schedule).hasStatusOk();
        assertThat(schedule).bodyJson().extractingPath("$[*].id").isEqualTo(List.of(vpn, printers));
        assertThat(schedule).bodyJson().extractingPath("$[1].team.name").isEqualTo("Hardware Team");
        assertThat(get(RITA, "/api/changes/schedule")).hasStatus(HttpStatus.FORBIDDEN);

        assertThat(get(NINA, "/api/tickets/" + vpn))
                .bodyJson()
                .extractingPath("$.change.clashes[*].id")
                .isEqualTo(List.of(printers));
        assertThat(get(NINA, "/api/tickets/" + later))
                .bodyJson()
                .extractingPath("$.change.clashes")
                .asArray()
                .isEmpty();
    }

    @Test
    void carryingOutAndReviewingAChange() {
        String change = id(create(HUGO, hardwareCategory, "STANDARD", "LOW", START, null));
        assignTo(change, hardware, hugo);
        transition(HUGO, change, "APPROVED", null);
        assertError(outcome(HUGO, change), HttpStatus.CONFLICT, "CHANGE_NOT_EDITABLE");
        assertThat(transition(HUGO, change, "IMPLEMENTING", null)).hasStatusOk();
        assertThat(transition(HUGO, change, "REVIEW", null)).hasStatusOk();
        assertError(
                transition(HUGO, change, "CLOSED", null), HttpStatus.UNPROCESSABLE_CONTENT, "CHANGE_REVIEW_INCOMPLETE");

        assertError(outcome(NINA, change), HttpStatus.FORBIDDEN, "CHANGE_UPDATE_NOT_PERMITTED");
        assertThat(outcome(HUGO, change)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(transition(HUGO, change, "CLOSED", null)).hasStatusOk();

        MvcTestResult closed = get(HUGO, "/api/tickets/" + change);
        assertThat(closed).bodyJson().extractingPath("$.change.outcome").isEqualTo("SUCCESSFUL_WITH_ISSUES");
        assertThat(closed).bodyJson().extractingPath("$.change.implementedAt").isNotNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'CHANGE_OUTCOME_RECORDED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aChangeCanFixAnOpenProblemAndTheProblemListsIt() {
        String problem = id(post(NINA, "/api/problems", """
                {"title": "VPN refuses connections", "description": "Error 809 at busy times",
                 "categoryId": "%s", "impact": "HIGH", "urgency": "MEDIUM"}""".formatted(networkCategory)));
        String change = id(create(NINA, networkCategory, "NORMAL", "MEDIUM", START, problem));

        assertThat(get(NINA, "/api/tickets/" + change))
                .bodyJson()
                .extractingPath("$.change.problem.id")
                .isEqualTo(problem);
        assertThat(get(NINA, "/api/tickets/" + problem))
                .bodyJson()
                .extractingPath("$.problem.changes[*].id")
                .isEqualTo(List.of(change));

        jdbc.update("update work_items set status = 'CANCELLED' where id = ?::uuid", problem);
        assertError(
                create(NINA, networkCategory, "NORMAL", "MEDIUM", START, problem),
                HttpStatus.CONFLICT,
                "PROBLEM_NOT_OPEN");
        assertError(
                create(
                        NINA,
                        networkCategory,
                        "NORMAL",
                        "MEDIUM",
                        START,
                        UUID.randomUUID().toString()),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    // ----- helpers ------------------------------------------------------------------------------

    private MvcTestResult create(
            String email, UUID category, String type, String risk, Instant start, String problemId) {
        return post(email, "/api/changes", """
                {"title": "Upgrade the VPN licence", "description": "More sessions",
                 "categoryId": "%s", "impact": "MEDIUM", "urgency": "MEDIUM",
                 "changeType": "%s", "risk": "%s", "implementationPlan": "Do it", "backoutPlan": "Undo it",
                 "plannedStart": %s, "plannedEnd": %s, "problemId": %s}""".formatted(
                category,
                type,
                risk,
                quoted(start == null ? null : start.toString()),
                quoted(start == null ? null : start.plus(Duration.ofHours(1)).toString()),
                quoted(problemId)));
    }

    private MvcTestResult plan(String email, String change, String type, String risk, Instant start) {
        return put(email, "/api/changes/" + change + "/plan", """
                {"changeType": "%s", "risk": "%s", "implementationPlan": "Do it", "backoutPlan": "Undo it",
                 "plannedStart": "%s", "plannedEnd": "%s", "version": %d}""".formatted(
                        type, risk, start, start.plus(Duration.ofHours(1)), version(change)));
    }

    private MvcTestResult outcome(String email, String change) {
        return put(
                email,
                "/api/changes/" + change + "/outcome",
                "{\"outcome\": \"SUCCESSFUL_WITH_ISSUES\", \"notes\": \"Two printers needed a second attempt.\","
                        + " \"version\": %d}".formatted(version(change)));
    }

    private void assign(String change) {
        assignTo(change, network, nina);
    }

    private void assignTo(String change, UUID team, User assignee) {
        assertThat(put(
                        assignee.getEmail(),
                        "/api/tickets/" + change + "/assignment",
                        "{\"teamId\": \"%s\", \"assigneeId\": \"%s\", \"version\": %d}"
                                .formatted(team, assignee.getId(), version(change))))
                .hasStatusOk();
    }

    private void decide(String email, String change, String decision, String comment) {
        String approval = jdbc.queryForObject(
                "select id::text from approvals where work_item_id = ?::uuid", String.class, change);
        long version = jdbc.queryForObject("select version from approvals where id = ?::uuid", Long.class, approval);
        assertThat(post(
                        email,
                        "/api/approvals/" + approval + "/decision",
                        "{\"decision\": \"%s\", \"comment\": %s, \"version\": %d}"
                                .formatted(decision, quoted(comment), version)))
                .hasStatusOk();
    }

    private List<String> available(String email, String ticket) {
        return JsonPath.read(body(get(email, "/api/tickets/" + ticket + "/transitions")), "$[*].targetStatus");
    }

    private MvcTestResult transition(String email, String ticket, String target, String reason) {
        return post(
                email,
                "/api/tickets/" + ticket + "/transitions",
                "{\"targetStatus\": \"%s\", \"version\": %d, \"reason\": %s}"
                        .formatted(target, version(ticket), quoted(reason)));
    }

    private long version(String ticket) {
        return jdbc.queryForObject("select version from work_items where id = ?::uuid", Long.class, ticket);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    private static String id(MvcTestResult result) {
        return JsonPath.read(body(result), "$.id");
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

    private MvcTestResult put(String email, String uri, String json) {
        return mvc.put()
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
