package com.ademola.esm.sla;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

/** Business-hours targets end to end: the calendar API, deadlines, queue badges and holidays. */
@IntegrationTest
class BusinessHoursApiIT {

    static final ZoneId LONDON = ZoneId.of("Europe/London");
    // 2027-01-08 is a Friday, 2027-01-11 the Monday after.
    static final LocalDate FRIDAY = LocalDate.of(2027, 1, 8);
    static final LocalDate MONDAY = LocalDate.of(2027, 1, 11);

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

    UUID category;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        UUID team = fixtures.team("Network Team");
        category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        users.create("requester@example.com", Role.REQUESTER);
        User agent = users.create("agent@example.com", Role.AGENT);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(team, agent.getId());
    }

    @AfterEach
    void restoreReferenceData() {
        clock.reset();
        jdbc.update("update sla_policies set business_hours = false, version = 0 where business_hours");
        jdbc.update("delete from business_hours");
        for (int day = 1; day <= 5; day++) {
            jdbc.update(
                    "insert into business_hours (day_of_week, opens_at, closes_at) values (?, '09:00', '17:30')", day);
        }
    }

    @Test
    void adminsSwitchAPolicyToBusinessHoursAndItIsAudited() {
        String id = jdbc.queryForObject(
                "select id::text from sla_policies where ticket_type = 'INCIDENT' and priority = 'P2'", String.class);

        MvcTestResult updated = mvc.put()
                .uri("/api/admin/sla-policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"responseMinutes\": 60, \"resolutionMinutes\": 480, \"businessHours\": true, \"version\": 0}")
                .exchange();

        assertThat(updated).hasStatusOk();
        assertThat(updated).bodyJson().extractingPath("$.businessHours").isEqualTo(true);
        assertThat(jdbc.queryForObject(
                        "select new_value->>'businessHours' from audit_events where action = 'SLA_POLICY_UPDATED'",
                        String.class))
                .isEqualTo("true");
    }

    @Test
    void aTicketRaisedLateOnFridayIsDueOnMondayInTheDetailAndTheQueueAlike() {
        businessHoursFor("P2");
        clock.setTo(at(FRIDAY, "16:45"));
        String id = incident(); // P2: first response within 60 working minutes

        MvcTestResult sla = get("/api/tickets/{id}/sla", id);
        assertThat(sla).bodyJson().extractingPath("$.clocks[0].metric").isEqualTo("RESPONSE");
        assertThat(sla).bodyJson().extractingPath("$.clocks[0].businessHours").isEqualTo(true);
        assertThat(instant(sla, "$.clocks[0].dueAt")).isEqualTo(at(MONDAY, "09:15"));
        assertThat(queue()).bodyJson().extractingPath("$.content[0].sla.dueAt").isNotNull();
        assertThat(instant(queue(), "$.content[0].sla.dueAt")).isEqualTo(at(MONDAY, "09:15"));

        // Over the weekend nothing counts: still on track on Monday morning.
        clock.setTo(at(MONDAY, "09:00"));
        assertBadge(id, "ON_TRACK");
        // 45 minutes were used on Friday (16:45 to 17:30); 80% of 60 is 48, reached at 09:03.
        clock.setTo(at(MONDAY, "09:10"));
        assertBadge(id, "AT_RISK");
        clock.setTo(at(MONDAY, "10:00"));
        assertBadge(id, "BREACHED");
    }

    @Test
    void aroundTheClockTicketsAreUnaffected() {
        businessHoursFor("P2");
        clock.setTo(at(FRIDAY, "16:45"));
        String p1 = incident("HIGH", "HIGH"); // P1 stays 24/7: 15 minutes

        assertThat(instant(get("/api/tickets/{id}/sla", p1), "$.clocks[0].dueAt"))
                .isEqualTo(at(FRIDAY, "17:00"));
        assertThat(get("/api/tickets/{id}/sla", p1))
                .bodyJson()
                .extractingPath("$.clocks[0].businessHours")
                .isEqualTo(false);
    }

    @Test
    void aHolidayAddedLaterMovesRunningDeadlinesAndRemovingItMovesThemBack() {
        businessHoursFor("P2");
        clock.setTo(at(FRIDAY, "16:45"));
        String id = incident();

        MvcTestResult added = mvc.post()
                .uri("/api/admin/business-hours/holidays")
                .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"day\": \"2027-01-11\", \"name\": \"Office move\"}")
                .exchange();
        assertThat(added).hasStatusOk();
        assertThat(added).bodyJson().extractingPath("$.holidays[0].name").isEqualTo("Office move");
        assertThat(instant(get("/api/tickets/{id}/sla", id), "$.clocks[0].dueAt"))
                .isEqualTo(at(MONDAY.plusDays(1), "09:15"));

        assertThat(mvc.post()
                        .uri("/api/admin/business-hours/holidays")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\": \"2027-01-11\", \"name\": \"Again\"}"))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_BUSINESS_HOURS");

        assertThat(mvc.delete()
                        .uri("/api/admin/business-hours/holidays/2027-01-11")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com")))
                .hasStatusOk();
        assertThat(instant(get("/api/tickets/{id}/sla", id), "$.clocks[0].dueAt"))
                .isEqualTo(at(MONDAY, "09:15"));
        assertThat(mvc.delete()
                        .uri("/api/admin/business-hours/holidays/2027-01-11")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com")))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theWeekIsReplacedWholeAndBrokenWeeksAreRefused() {
        MvcTestResult calendar = mvc.get()
                .uri("/api/admin/business-hours")
                .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com"))
                .exchange();
        assertThat(calendar).bodyJson().extractingPath("$.zone").isEqualTo("Europe/London");
        assertThat(calendar).bodyJson().extractingPath("$.week").asArray().hasSize(5);

        MvcTestResult saturdays = putWeek("[{\"day\": \"SATURDAY\", \"opensAt\": \"10:00\", \"closesAt\": \"14:00\"}]");
        assertThat(saturdays).hasStatusOk();
        assertThat(saturdays)
                .bodyJson()
                .extractingPath("$.week[*].day")
                .asArray()
                .containsExactly("SATURDAY");

        assertThat(putWeek("[{\"day\": \"MONDAY\", \"opensAt\": \"17:00\", \"closesAt\": \"09:00\"}]"))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_BUSINESS_HOURS");
        assertThat(putWeek("[{\"day\": \"MONDAY\", \"opensAt\": \"09:00\", \"closesAt\": \"17:00\"},"
                        + " {\"day\": \"MONDAY\", \"opensAt\": \"10:00\", \"closesAt\": \"12:00\"}]"))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_BUSINESS_HOURS");
        assertThat(putWeek("[]")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("select count(*) from business_hours", Integer.class))
                .as("refused changes leave the week as it was")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'BUSINESS_HOURS_UPDATED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void onlyAdminsSeeOrChangeTheCalendar() {
        assertThat(mvc.get()
                        .uri("/api/admin/business-hours")
                        .header(HttpHeaders.AUTHORIZATION, bearer("agent@example.com")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.put()
                        .uri("/api/admin/business-hours/week")
                        .header(HttpHeaders.AUTHORIZATION, bearer("agent@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"days\": [{\"day\": \"MONDAY\", \"opensAt\": \"09:00\", \"closesAt\": \"17:00\"}]}"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ----- helpers ------------------------------------------------------------------------------

    private void businessHoursFor(String priority) {
        jdbc.update(
                "update sla_policies set business_hours = true where ticket_type = 'INCIDENT' and priority = ?",
                priority);
    }

    private void assertBadge(String id, String expected) {
        assertThat(get("/api/tickets/{id}/sla", id))
                .bodyJson()
                .extractingPath("$.clocks[0].status")
                .isEqualTo(expected);
        assertThat(queue()).bodyJson().extractingPath("$.content[0].sla.status").isEqualTo(expected);
    }

    private MvcTestResult putWeek(String days) {
        return mvc.put()
                .uri("/api/admin/business-hours/week")
                .header(HttpHeaders.AUTHORIZATION, bearer("admin@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\": " + days + "}")
                .exchange();
    }

    private String incident() {
        return incident("MEDIUM", "HIGH");
    }

    private String incident(String impact, String urgency) {
        MvcTestResult result = mvc.post()
                .uri("/api/incidents")
                .header(HttpHeaders.AUTHORIZATION, bearer("requester@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s",
                         "impact": "%s", "urgency": "%s"}
                        """.formatted(category, impact, urgency))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private MvcTestResult queue() {
        return mvc.get()
                .uri("/api/tickets?view=ALL")
                .header(HttpHeaders.AUTHORIZATION, bearer("agent@example.com"))
                .exchange();
    }

    private MvcTestResult get(String path, Object... vars) {
        return mvc.get()
                .uri(path, vars)
                .header(HttpHeaders.AUTHORIZATION, bearer("agent@example.com"))
                .exchange();
    }

    private String bearer(String email) {
        return loginOk(mvc, email).bearer();
    }

    /** To the second: the test clock keeps moving for a few milliseconds after it is set. */
    private static Instant instant(MvcTestResult result, String path) {
        return Instant.parse(JsonPath.<String>read(body(result), path)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant at(LocalDate day, String time) {
        return LocalDateTime.of(day, LocalTime.parse(time)).atZone(LONDON).toInstant();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
