package com.ademola.esm.reporting;

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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The service desk report: what it counts, for whom, and over which days. */
@IntegrationTest
class ReportApiIT {

    // March 2027, before the clocks change: London is on UTC.
    static final Instant MON_1 = Instant.parse("2027-03-01T10:00:00Z");
    static final Instant TUE_2 = Instant.parse("2027-03-02T10:00:00Z");

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

    UUID network;
    UUID hardware;
    UUID networkCategory;
    UUID hardwareCategory;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        network = fixtures.team("Network Team");
        hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        hardwareCategory = fixtures.category("HARDWARE", "Hardware", null, hardware, "INCIDENT");
        users.create("requester@example.com", Role.REQUESTER);
        users.create("agent@example.com", Role.AGENT);
        User lead = users.create("lead@example.com", Role.TEAM_LEAD);
        users.create("lonely@example.com", Role.TEAM_LEAD);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(network, lead.getId());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void countsVolumesBacklogMediansAndCategoriesForThePeriod() {
        clock.setTo(MON_1);
        String fast = incident(networkCategory, "HIGH", "HIGH"); // P1
        String slow = incident(networkCategory, "HIGH", "HIGH"); // P1
        incident(hardwareCategory, "LOW", "LOW"); // P4, stays open
        clock.setTo(TUE_2);
        incident(networkCategory, "LOW", "LOW"); // P4, stays open
        respondAndResolve(fast, MON_1.plus(Duration.ofMinutes(10)), MON_1.plus(Duration.ofMinutes(60)));
        respondAndResolve(slow, MON_1.plus(Duration.ofMinutes(30)), TUE_2.plus(Duration.ofMinutes(60)));

        MvcTestResult report = report("admin@example.com", "from=2027-03-01&to=2027-03-03");

        assertThat(report).hasStatusOk();
        assertThat(report).bodyJson().extractingPath("$.zone").isEqualTo("Europe/London");
        assertThat(report).bodyJson().extractingPath("$.created").isEqualTo(4);
        assertThat(report).bodyJson().extractingPath("$.resolved").isEqualTo(2);
        assertThat(report).bodyJson().extractingPath("$.openNow").isEqualTo(2);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.daily[*].created")
                .asArray()
                .containsExactly(3, 1, 0);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.daily[*].resolved")
                .asArray()
                .containsExactly(1, 1, 0);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.backlogByPriority[0].priority")
                .isEqualTo("P4");
        assertThat(report)
                .bodyJson()
                .extractingPath("$.backlogByPriority[0].count")
                .isEqualTo(2);
        // Medians of 10 and 30 minutes, and of 60 and 1500 minutes.
        assertThat(report).bodyJson().extractingPath("$.times[0].priority").isEqualTo("P1");
        assertThat(report)
                .bodyJson()
                .extractingPath("$.times[0].firstResponseMinutes")
                .isEqualTo(20.0);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.times[0].resolutionMinutes")
                .isEqualTo(780.0);
        assertThat(report).bodyJson().extractingPath("$.times[0].resolvedCount").isEqualTo(2);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.times[3].resolutionMinutes")
                .isNull();
        assertThat(report).bodyJson().extractingPath("$.topCategories[0].name").isEqualTo("Network");
        assertThat(report).bodyJson().extractingPath("$.topCategories[0].count").isEqualTo(3);

        assertThat(report("admin@example.com", "from=2027-03-02&to=2027-03-02"))
                .bodyJson()
                .extractingPath("$.created")
                .isEqualTo(1);
    }

    @Test
    void slaComplianceCountsTargetsFinishedInThePeriodAndOpenBreaches() {
        clock.setTo(MON_1);
        String met = incident(networkCategory, "HIGH", "HIGH");
        String missed = incident(networkCategory, "HIGH", "HIGH");
        String breachedOpen = incident(networkCategory, "HIGH", "HIGH");
        completeClock(met, "RESPONSE", MON_1.plusSeconds(60), false);
        completeClock(missed, "RESPONSE", MON_1.plusSeconds(3600), true);
        jdbc.update(
                "update sla_clocks set breached_at = ? where work_item_id = ?::uuid and metric = 'RESOLUTION'",
                Timestamp.from(MON_1.plusSeconds(7200)),
                breachedOpen);

        MvcTestResult report = report("admin@example.com", "from=2027-03-01&to=2027-03-01");

        assertThat(report).bodyJson().extractingPath("$.sla[0].metric").isEqualTo("RESPONSE");
        assertThat(report).bodyJson().extractingPath("$.sla[0].met").isEqualTo(1);
        assertThat(report).bodyJson().extractingPath("$.sla[0].missed").isEqualTo(1);
        assertThat(report).bodyJson().extractingPath("$.sla[0].percentMet").isEqualTo(50.0);
        assertThat(report).bodyJson().extractingPath("$.breachedOpen").isEqualTo(1);
    }

    @Test
    void teamLeadsSeeOnlyTheirOwnTeams() {
        clock.setTo(MON_1);
        incident(networkCategory, "LOW", "LOW");
        incident(hardwareCategory, "LOW", "LOW");

        assertThat(report("lead@example.com", "from=2027-03-01&to=2027-03-01"))
                .bodyJson()
                .extractingPath("$.created")
                .isEqualTo(1);
        MvcTestResult other = report("lead@example.com", "from=2027-03-01&to=2027-03-01&teamId=" + hardware);
        assertThat(other).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(report("admin@example.com", "from=2027-03-01&to=2027-03-01&teamId=" + hardware))
                .bodyJson()
                .extractingPath("$.created")
                .isEqualTo(1);
        MvcTestResult noTeams = report("lonely@example.com", "from=2027-03-01&to=2027-03-02");
        assertThat(noTeams).bodyJson().extractingPath("$.created").isEqualTo(0);
        assertThat(noTeams).bodyJson().extractingPath("$.daily").asArray().hasSize(2);
        assertThat(report("agent@example.com", "")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void changesClosedInThePeriodAreCountedByOutcome() {
        clock.setTo(MON_1);
        String id = incident(networkCategory, "LOW", "LOW");
        // A closed change, written directly: its lifecycle has its own tests.
        UUID change = UUID.randomUUID();
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, requester_id, assigned_team_id, closed_at, created_at, updated_at)"
                        + " select ?, 'CHG-000001', 'CHANGE', 'Patch', 'x', 'CLOSED', 'LOW', 'LOW', 'P4', category_id,"
                        + " requester_id, assigned_team_id, ?, ?, ? from work_items where id = ?::uuid",
                change,
                Timestamp.from(MON_1),
                Timestamp.from(MON_1),
                Timestamp.from(MON_1),
                id);
        jdbc.update(
                "insert into changes (work_item_id, change_type, risk, outcome, review_notes)"
                        + " values (?, 'NORMAL', 'LOW', 'SUCCESSFUL', 'Fine')",
                change);

        MvcTestResult report = report("admin@example.com", "from=2027-03-01&to=2027-03-01");

        assertThat(report)
                .bodyJson()
                .extractingPath("$.changeOutcomes[0].outcome")
                .isEqualTo("SUCCESSFUL");
        assertThat(report)
                .bodyJson()
                .extractingPath("$.changeOutcomes[0].count")
                .isEqualTo(1);
        assertThat(report)
                .bodyJson()
                .extractingPath("$.created")
                .as("changes aren't desk work")
                .isEqualTo(1);
    }

    @Test
    void thePeriodDefaultsToTheLastThirtyDaysAndIsChecked() {
        clock.setTo(MON_1);

        MvcTestResult defaults = report("admin@example.com", "");
        assertThat(defaults).bodyJson().extractingPath("$.to").isEqualTo("2027-03-01");
        assertThat(defaults).bodyJson().extractingPath("$.from").isEqualTo("2027-01-31");
        assertThat(defaults).bodyJson().extractingPath("$.daily").asArray().hasSize(30);

        MvcTestResult backwards = report("admin@example.com", "from=2027-03-02&to=2027-03-01");
        assertThat(backwards).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(backwards).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REPORT_PERIOD");
        assertThat(report("admin@example.com", "from=2025-01-01&to=2027-03-01"))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_REPORT_PERIOD");
    }

    // ----- helpers ------------------------------------------------------------------------------

    private void respondAndResolve(String id, Instant responded, Instant resolved) {
        jdbc.update(
                "update work_items set status = 'RESOLVED', first_responded_at = ?, resolved_at = ? where id = ?::uuid",
                Timestamp.from(responded),
                Timestamp.from(resolved),
                id);
    }

    private void completeClock(String id, String metric, Instant at, boolean breached) {
        jdbc.update(
                "update sla_clocks set state = 'COMPLETED', running_since = null, due_at = null, at_risk_at = null,"
                        + " completed_at = ?, breached_at = ? where work_item_id = ?::uuid and metric = ?",
                Timestamp.from(at),
                breached ? Timestamp.from(at.minusSeconds(1)) : null,
                id,
                metric);
    }

    private String incident(UUID category, String impact, String urgency) {
        MvcTestResult result = mvc.post()
                .uri("/api/incidents")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        loginOk(mvc, "requester@example.com").bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Broken", "description": "x", "categoryId": "%s", "impact": "%s", "urgency": "%s"}
                        """.formatted(category, impact, urgency))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String id = JsonPath.read(body(result), "$.id");
        // created_at is stamped by Hibernate from the JVM clock, not the test clock: set it to "now".
        jdbc.update("update work_items set created_at = ? where id = ?::uuid", Timestamp.from(clock.instant()), id);
        return id;
    }

    private MvcTestResult report(String email, String query) {
        return mvc.get()
                .uri("/api/reports/service-desk" + (query.isEmpty() ? "" : "?" + query))
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
