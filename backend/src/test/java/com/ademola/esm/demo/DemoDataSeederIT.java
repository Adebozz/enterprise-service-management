package com.ademola.esm.demo;

import static com.ademola.esm.support.AuthHelper.login;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.IntegrationTest;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Starts the application with the demo profile (its own context and database) and checks that the
 * seed data went through the real business rules.
 */
@IntegrationTest
@ActiveProfiles("demo")
@TestPropertySource(properties = "esm.demo.password=demo-password-for-tests")
class DemoDataSeederIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DemoDataSeeder seeder;

    @Test
    void seedsTicketsInEveryInterestingStateThroughTheRealRules() {
        Map<String, Integer> byStatus = jdbc.queryForList("select reference, status from work_items").stream()
                .collect(Collectors.toMap(row -> (String) row.get("status"), row -> 1, Integer::sum));

        assertThat(byStatus)
                .containsKeys(
                        "NEW",
                        "ASSIGNED",
                        "WAITING_FOR_USER",
                        "RESOLVED",
                        "CLOSED",
                        "FULFILLED",
                        "SUBMITTED",
                        "CANCELLED");
        // Priority came from the matrix, not from the seeder: HIGH x HIGH => P1.
        assertThat(jdbc.queryForObject(
                        "select priority from work_items where title = 'VPN won''t connect from home'", String.class))
                .isEqualTo("P1");
        // Every ticket has a genuine audit trail with a real actor.
        assertThat(jdbc.queryForObject(
                        "select count(*) from work_items w where not exists (select 1 from audit_events a"
                                + " where a.entity_id = w.id and a.action = 'TICKET_CREATED' and a.actor_id is not null)",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from comments where visibility = 'INTERNAL'", Integer.class))
                .isPositive();
    }

    @Test
    void runningAgainChangesNothing() {
        int tickets = count("work_items");
        int users = count("users");
        int events = count("audit_events");

        seeder.run(null);

        assertThat(count("work_items")).isEqualTo(tickets);
        assertThat(count("users")).isEqualTo(users);
        assertThat(count("audit_events")).isEqualTo(events);
    }

    @Test
    void demoAccountsCanSignInAndSeeOnlyWhatTheirRoleAllows() throws Exception {
        MvcTestResult rita = login(mvc, "rita@demo.local", "demo-password-for-tests");
        assertThat(rita).hasStatusOk();
        String token = com.jayway.jsonpath.JsonPath.read(rita.getResponse().getContentAsString(), "$.accessToken");

        MvcTestResult ritasTickets = mvc.get()
                .uri("/api/tickets?view=ALL&size=100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();

        assertThat(ritasTickets)
                .bodyJson()
                .extractingPath("$.content[*].requester.name")
                .asArray()
                .isNotEmpty()
                .allMatch("Rita Requester"::equals);
        assertThat(login(mvc, "nina@demo.local", "demo-password-for-tests")).hasStatusOk();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
