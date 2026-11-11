package com.ademola.esm.ticket.problem;

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

/** Problem management end to end: raising, investigating, linking incidents and fixing the cause. */
@IntegrationTest
class ProblemApiIT {

    static final String NINA = "nina@example.com"; // Network agent: owns network problems
    static final String HUGO = "hugo@example.com"; // Hardware agent: another team
    static final String RITA = "rita@example.com"; // requester

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
    UUID accessCategory;
    User nina;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        network = fixtures.team("Network Team");
        UUID hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        accessCategory = fixtures.category("ACCESS", "Access", null, hardware, "SERVICE_REQUEST");
        nina = users.create(NINA, Role.AGENT);
        User hugo = users.create(HUGO, Role.AGENT);
        users.create(RITA, Role.REQUESTER);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(network, nina.getId());
        fixtures.member(hardware, hugo.getId());
    }

    @Test
    void supportRaisesProblemsRoutedLikeIncidentsAndWithoutServiceLevels() {
        MvcTestResult created = createProblem(NINA, networkCategory, null);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.reference").asString().matches("PRB-\\d{6}");
        assertThat(created).bodyJson().extractingPath("$.type").isEqualTo("PROBLEM");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        String problem = id(created);
        assertThat(get(NINA, "/api/tickets/" + problem))
                .bodyJson()
                .extractingPath("$.assignedTeam.name")
                .isEqualTo("Network Team");
        assertThat(jdbc.queryForObject("select count(*) from sla_clocks", Integer.class))
                .isZero();

        assertThat(createProblem(RITA, networkCategory, null)).hasStatus(HttpStatus.FORBIDDEN);
        assertError(createProblem(NINA, accessCategory, null), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_CATEGORY");
    }

    @Test
    void everyAgentCanSeeProblemsButRequestersCannot() {
        MvcTestResult created = createProblem(NINA, networkCategory, null);
        String problem = id(created);
        String reference = JsonPath.read(body(created), "$.reference");

        assertThat(get(HUGO, "/api/tickets/" + problem)).hasStatusOk();
        assertThat(get(HUGO, "/api/tickets?view=ALL&type=PROBLEM"))
                .bodyJson()
                .extractingPath("$.content[*].reference")
                .isEqualTo(List.of(reference));
        // Searching by reference works with the short form too ("prb-6" for PRB-000006).
        assertThat(get(HUGO, "/api/tickets?view=ALL&q=prb-" + Integer.parseInt(reference.substring(4))))
                .bodyJson()
                .extractingPath("$.totalElements")
                .isEqualTo(1);
        assertThat(get(RITA, "/api/tickets/" + problem)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(RITA, "/api/tickets?view=ALL&type=PROBLEM"))
                .bodyJson()
                .extractingPath("$.totalElements")
                .isEqualTo(0);
    }

    @Test
    void raisingAProblemFromAnIncidentLinksThem() {
        String incident = reportIncident();
        MvcTestResult created = createProblem(NINA, networkCategory, incident);
        String problem = id(created);

        assertThat(get(NINA, "/api/tickets/" + problem))
                .bodyJson()
                .extractingPath("$.problem.incidents[*].id")
                .isEqualTo(List.of(incident));
        assertThat(get(NINA, "/api/tickets/" + incident))
                .bodyJson()
                .extractingPath("$.incident.problem.reference")
                .isEqualTo(JsonPath.read(body(created), "$.reference"));
        // The requester sees their incident, not the investigation behind it.
        assertThat(get(RITA, "/api/tickets/" + incident))
                .bodyJson()
                .extractingPath("$.incident.problem")
                .isNull();
        // Hugo can't see Rita's network incident, so to him it doesn't exist.
        assertError(createProblem(HUGO, networkCategory, incident), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void onlyTheOwningTeamRecordsTheAnalysis() {
        String problem = id(createProblem(NINA, networkCategory, null));

        assertError(
                analysis(HUGO, problem, "Cause", "Workaround", 0),
                HttpStatus.FORBIDDEN,
                "PROBLEM_UPDATE_NOT_PERMITTED");
        assertError(analysis(NINA, problem, "Cause", "Workaround", 7), HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        assertThat(analysis(NINA, problem, "Gateway licence is full", null, 0)).hasStatus(HttpStatus.NO_CONTENT);
        // Saving the same analysis again changes nothing and records nothing.
        assertThat(analysis(NINA, problem, "Gateway licence is full", null, 1)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult saved = get(NINA, "/api/tickets/" + problem);
        assertThat(saved).bodyJson().extractingPath("$.problem.rootCause").isEqualTo("Gateway licence is full");
        assertThat(saved).bodyJson().extractingPath("$.problem.workaround").isNull();
        assertThat(jdbc.queryForObject(
                        "select new_value->>'rootCause' from audit_events where action = 'PROBLEM_ANALYSIS_UPDATED'",
                        String.class))
                .isEqualTo("Gateway licence is full");
    }

    @Test
    void aKnownErrorShowsItsWorkaroundOnTheIncidentsItExplains() {
        String incident = reportIncident();
        String problem = id(createProblem(NINA, networkCategory, incident));
        investigate(problem);

        assertError(
                transition(NINA, problem, "KNOWN_ERROR", null, null),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "PROBLEM_ANALYSIS_INCOMPLETE");
        assertThat(analysis(NINA, problem, "Gateway licence is full", "Use vpn2", version(problem)))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(transition(NINA, problem, "KNOWN_ERROR", null, null)).hasStatusOk();

        MvcTestResult seen = get(NINA, "/api/tickets/" + incident);
        assertThat(seen).bodyJson().extractingPath("$.incident.problem.status").isEqualTo("KNOWN_ERROR");
        assertThat(seen)
                .bodyJson()
                .extractingPath("$.incident.problem.workaround")
                .isEqualTo("Use vpn2");
    }

    @Test
    void fixingTheProblemTellsTheIncidentsStillOpen() {
        String open = reportIncident();
        String done = reportIncident();
        MvcTestResult created = createProblem(NINA, networkCategory, open);
        String problem = id(created);
        String reference = JsonPath.read(body(created), "$.reference");
        assertThat(link(NINA, done, problem)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.update("update work_items set status = 'CANCELLED' where id = ?::uuid", done))
                .isOne();
        investigate(problem);
        analysis(NINA, problem, "Gateway licence is full", null, version(problem));

        assertError(
                transition(NINA, problem, "RESOLVED", null, null),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "TRANSITION_REQUIREMENT_MISSING");
        assertThat(transition(NINA, problem, "RESOLVED", null, "Licence raised to 500 sessions"))
                .hasStatusOk();

        MvcTestResult notes = get(NINA, "/api/tickets/" + open + "/comments");
        assertThat(notes).bodyJson().extractingPath("$[0].visibility").isEqualTo("INTERNAL");
        assertThat(notes)
                .bodyJson()
                .extractingPath("$[0].body")
                .asString()
                .contains(reference + " has been fixed: Licence raised to 500 sessions");
        assertThat(get(RITA, "/api/tickets/" + open + "/comments")).bodyJson().isEqualTo("[]");
        assertThat(jdbc.queryForObject(
                        "select count(*) from comments where work_item_id = ?::uuid", Integer.class, done))
                .isZero();
        // A fixed problem takes no more incidents.
        assertError(link(NINA, reportIncident(), problem), HttpStatus.CONFLICT, "PROBLEM_NOT_OPEN");
    }

    @Test
    void linksCanBeRemovedAndAreAudited() {
        String incident = reportIncident();
        String problem = id(createProblem(NINA, networkCategory, null));
        assertThat(link(NINA, incident, problem)).hasStatus(HttpStatus.NO_CONTENT);

        assertError(unlink(NINA, incident, 0), HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        assertThat(unlink(NINA, incident, version(incident))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(unlink(NINA, incident, version(incident))).hasStatus(HttpStatus.NO_CONTENT); // already gone

        assertThat(get(NINA, "/api/tickets/" + incident))
                .bodyJson()
                .extractingPath("$.incident.problem")
                .isNull();
        assertThat(jdbc.queryForList(
                        "select action from audit_events where entity_id = ?::uuid and action like 'INCIDENT_PROBLEM_%'"
                                + " order by occurred_at",
                        String.class, incident))
                .containsExactly("INCIDENT_PROBLEM_LINKED", "INCIDENT_PROBLEM_UNLINKED");
    }

    @Test
    void linkingNeedsSomeoneHandlingTheIncidentOrTheProblem() {
        String incident = reportIncident();
        MvcTestResult created = createProblem(NINA, networkCategory, null);
        String problem = id(created);
        // Rita's incident moved to Hugo's team: Hugo handles it, Nina still owns the problem.
        String hardware = jdbc.queryForObject("select id::text from teams where name = 'Hardware Team'", String.class);
        jdbc.update("update work_items set assigned_team_id = ?::uuid where id = ?::uuid", hardware, incident);
        String hugosProblem = id(createProblem(HUGO, networkCategory, null)); // network problems belong to Network

        assertThat(link(RITA, incident, problem)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(link(HUGO, incident, problem)).hasStatus(HttpStatus.NO_CONTENT); // handles the incident
        assertThat(link(NINA, incident, hugosProblem)).hasStatus(HttpStatus.NOT_FOUND); // can't see it any more
        assertThat(link("admin@example.com", incident, hugosProblem)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
                        "select old_value->>'problemReference' from audit_events where action = 'INCIDENT_PROBLEM_LINKED'"
                                + " and old_value->>'problemReference' is not null",
                        String.class))
                .isEqualTo(JsonPath.read(body(created), "$.reference"));
        assertError(link(NINA, UUID.randomUUID().toString(), problem), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void raisingAnIncidentIsNotTheSameAsHandlingIt() {
        // Hugo reported a network incident himself: he can see it, but Network handles it.
        String incident = id(post(HUGO, "/api/incidents", """
                {"title": "VPN drops", "description": "Again", "categoryId": "%s", "impact": "LOW", "urgency": "LOW"}""".formatted(networkCategory)));
        String problem = id(createProblem(NINA, networkCategory, null));

        assertError(
                createProblem(HUGO, networkCategory, incident), HttpStatus.FORBIDDEN, "INCIDENT_LINK_NOT_PERMITTED");
        assertError(link(HUGO, incident, problem), HttpStatus.FORBIDDEN, "INCIDENT_LINK_NOT_PERMITTED");
        assertThat(link(NINA, incident, problem)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(link(NINA, incident, problem)).hasStatus(HttpStatus.NO_CONTENT); // already linked: nothing to do
        assertError(unlink(HUGO, incident, version(incident)), HttpStatus.FORBIDDEN, "INCIDENT_LINK_NOT_PERMITTED");

        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'INCIDENT_PROBLEM_LINKED'", Integer.class))
                .isOne();
        assertError(link(NINA, incident, UUID.randomUUID().toString()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(
                analysis(NINA, UUID.randomUUID().toString(), "Cause", null, 0),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        // Admins may record any problem's analysis.
        assertThat(analysis("admin@example.com", problem, "Cause", null, version(problem)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    // ----- helpers ------------------------------------------------------------------------------

    private String reportIncident() {
        MvcTestResult created = post(RITA, "/api/incidents", """
                {"title": "VPN error 809", "description": "Can't connect", "categoryId": "%s",
                 "impact": "MEDIUM", "urgency": "MEDIUM"}""".formatted(networkCategory));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return id(created);
    }

    private MvcTestResult createProblem(String email, UUID category, String incidentId) {
        String incident = incidentId == null ? "" : ", \"incidentId\": \"%s\"".formatted(incidentId);
        return post(email, "/api/problems", """
                {"title": "VPN refuses connections", "description": "Error 809 at busy times",
                 "categoryId": "%s", "impact": "HIGH", "urgency": "MEDIUM"%s}""".formatted(category, incident));
    }

    /** Assigns the problem to Nina and starts the investigation. */
    private void investigate(String problem) {
        assertThat(put(
                        NINA,
                        "/api/tickets/" + problem + "/assignment",
                        "{\"teamId\": \"%s\", \"assigneeId\": \"%s\", \"version\": %d}"
                                .formatted(network, nina.getId(), version(problem))))
                .hasStatusOk();
        assertThat(transition(NINA, problem, "INVESTIGATING", null, null)).hasStatusOk();
    }

    private MvcTestResult analysis(String email, String problem, String rootCause, String workaround, long version) {
        return put(
                email,
                "/api/problems/" + problem + "/analysis",
                "{\"rootCause\": %s, \"workaround\": %s, \"version\": %d}"
                        .formatted(quoted(rootCause), quoted(workaround), version));
    }

    private MvcTestResult transition(String email, String ticket, String target, String reason, String notes) {
        return post(
                email,
                "/api/tickets/" + ticket + "/transitions",
                "{\"targetStatus\": \"%s\", \"version\": %d, \"reason\": %s, \"notes\": %s}"
                        .formatted(target, version(ticket), quoted(reason), quoted(notes)));
    }

    private MvcTestResult link(String email, String incident, String problem) {
        long version = incident.length() == 36 && exists(incident) ? version(incident) : 0;
        return put(
                email,
                "/api/incidents/" + incident + "/problem",
                "{\"problemId\": \"%s\", \"version\": %d}".formatted(problem, version));
    }

    private MvcTestResult unlink(String email, String incident, long version) {
        return mvc.delete()
                .uri("/api/incidents/" + incident + "/problem?version=" + version)
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .exchange();
    }

    private boolean exists(String ticket) {
        return jdbc.queryForObject("select count(*) from work_items where id = ?::uuid", Integer.class, ticket) == 1;
    }

    private long version(String ticket) {
        if (!exists(ticket)) {
            return 0;
        }
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
