package com.ademola.esm.ticket;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.common.web.CorrelationIdFilter;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import java.util.Map;
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

/** Raising tickets and reading them back, with real tokens, routing, priority, audit and access rules. */
@IntegrationTest
class TicketApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    UUID networkTeam;
    UUID hardwareTeam;
    UUID networkCategory;
    UUID wifiSubcategory;
    UUID firewallSubcategory;
    UUID accessRequestCategory;
    User requester;
    User otherRequester;
    User networkAgent;
    User hardwareAgent;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        networkTeam = fixtures.team("Network Team");
        hardwareTeam = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, networkTeam, "INCIDENT");
        wifiSubcategory = fixtures.category("WIFI", "Wi-Fi", networkCategory, null, "INCIDENT");
        firewallSubcategory = fixtures.category("FIREWALL", "Firewall", networkCategory, hardwareTeam, "INCIDENT");
        accessRequestCategory = fixtures.category("ACCESS", "Access request", null, hardwareTeam, "SERVICE_REQUEST");

        requester = users.create("requester@example.com", Role.REQUESTER);
        otherRequester = users.create("other@example.com", Role.REQUESTER);
        networkAgent = users.create("netagent@example.com", Role.AGENT);
        hardwareAgent = users.create("hwagent@example.com", Role.AGENT);
        users.create("admin@example.com", Role.ADMIN);
        fixtures.member(networkTeam, networkAgent.getId());
        fixtures.member(hardwareTeam, hardwareAgent.getId());
    }

    // ----- creating incidents -------------------------------------------------------------------

    @Test
    void requesterRaisesIncidentThatIsPrioritisedRoutedAndReadable() {
        String token = loginOk(mvc, "requester@example.com").bearer();

        MvcTestResult created = createIncident(token, incidentJson(networkCategory, wifiSubcategory, "HIGH", "MEDIUM"));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.reference").asString().matches("INC-\\d{6}");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        assertThat(created).bodyJson().extractingPath("$.priority").isEqualTo("P2"); // HIGH impact, MEDIUM urgency
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);

        MvcTestResult detail = get(location, token);
        assertThat(detail).hasStatusOk();
        assertThat(detail).bodyJson().extractingPath("$.type").isEqualTo("INCIDENT");
        assertThat(detail).bodyJson().extractingPath("$.category.name").isEqualTo("Network");
        assertThat(detail).bodyJson().extractingPath("$.subcategory.name").isEqualTo("Wi-Fi");
        assertThat(detail).bodyJson().extractingPath("$.assignedTeam.name").isEqualTo("Network Team");
        assertThat(detail)
                .bodyJson()
                .extractingPath("$.requester.id")
                .isEqualTo(requester.getId().toString());
        assertThat(detail).bodyJson().extractingPath("$.assignee").isNull();
        assertThat(detail)
                .bodyJson()
                .extractingPath("$.incident.affectedService")
                .isEqualTo("Office Wi-Fi");
        assertThat(detail).bodyJson().extractingPath("$.serviceRequest").isNull();
    }

    @Test
    void subcategoryWithItsOwnTeamOverridesRouting() {
        String token = loginOk(mvc, "requester@example.com").bearer();

        MvcTestResult created = createIncident(token, incidentJson(networkCategory, firewallSubcategory, "LOW", "LOW"));

        assertThat(get(created.getResponse().getHeader(HttpHeaders.LOCATION), token))
                .bodyJson()
                .extractingPath("$.assignedTeam.name")
                .isEqualTo("Hardware Team");
    }

    @Test
    void creationIsAuditedInTheSameTransactionWithActorAndCorrelationId() {
        String token = loginOk(mvc, "requester@example.com").bearer();

        MvcTestResult created = mvc.post()
                .uri("/api/incidents")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(CorrelationIdFilter.HEADER, "audit-correlation-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(incidentJson(networkCategory, null, "MEDIUM", "MEDIUM"))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);

        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_id, action, entity_type, new_value::text as new_value, correlation_id from audit_events"
                        + " where action = 'TICKET_CREATED'");
        assertThat(audit.get("actor_id")).isEqualTo(requester.getId());
        assertThat(audit.get("entity_type")).isEqualTo("WORK_ITEM");
        assertThat(audit.get("correlation_id")).isEqualTo("audit-correlation-01");
        assertThat((String) audit.get("new_value"))
                .contains("\"priority\": \"P3\"")
                .contains("\"assignedTeamId\": \"" + networkTeam + "\"");
    }

    @Test
    void invalidCategoryChoicesAreRejectedAs422() {
        String token = loginOk(mvc, "requester@example.com").bearer();
        UUID subOfOtherParent = fixtures.category("LAPTOP", "Laptop", accessRequestCategory, null, "SERVICE_REQUEST");
        jdbc.update("update categories set active = false where code = 'FIREWALL'");

        for (String body : new String[] {
            incidentJson(accessRequestCategory, null, "LOW", "LOW"), // service-request category on an incident
            incidentJson(networkCategory, subOfOtherParent, "LOW", "LOW"), // subcategory of another parent
            incidentJson(networkCategory, firewallSubcategory, "LOW", "LOW"), // inactive subcategory
            incidentJson(UUID.randomUUID(), null, "LOW", "LOW") // unknown category
        }) {
            MvcTestResult result = createIncident(token, body);
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CATEGORY");
        }
        assertThat(jdbc.queryForObject("select count(*) from work_items", Integer.class))
                .isZero();
    }

    @Test
    void missingRequiredFieldsAreReportedTogether() {
        MvcTestResult result =
                createIncident(loginOk(mvc, "requester@example.com").bearer(), "{\"title\": \" \"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.fieldErrors[*].field")
                .asArray()
                .contains("title", "description", "categoryId", "impact", "urgency");
    }

    // ----- service requests ---------------------------------------------------------------------

    @Test
    void serviceRequestDefaultsToLowImpactMediumUrgency() {
        String token = loginOk(mvc, "requester@example.com").bearer();

        MvcTestResult created = createServiceRequest(token, """
                {"title": "VPN access", "description": "Need VPN for remote work", "categoryId": "%s"}
                """.formatted(accessRequestCategory));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.reference").asString().matches("REQ-\\d{6}");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("SUBMITTED");
        assertThat(created).bodyJson().extractingPath("$.priority").isEqualTo("P4"); // LOW x MEDIUM

        MvcTestResult detail = get(created.getResponse().getHeader(HttpHeaders.LOCATION), token);
        assertThat(detail).bodyJson().extractingPath("$.serviceRequest").isNotNull();
        assertThat(detail).bodyJson().extractingPath("$.incident").isNull();
        assertThat(detail).bodyJson().extractingPath("$.assignedTeam.name").isEqualTo("Hardware Team");
    }

    @Test
    void urgentServiceRequestGetsHigherPriority() {
        MvcTestResult created = createServiceRequest(
                loginOk(mvc, "requester@example.com").bearer(), """
                {"title": "Laptop broken before client demo", "description": "Need a loaner",
                 "categoryId": "%s", "urgency": "HIGH"}
                """.formatted(accessRequestCategory));

        assertThat(created).bodyJson().extractingPath("$.priority").isEqualTo("P3"); // LOW x HIGH
    }

    // ----- visibility ---------------------------------------------------------------------------

    @Test
    void ticketVisibilityFollowsOwnershipAndTeamScope() {
        String location = createIncident(
                        loginOk(mvc, "requester@example.com").bearer(),
                        incidentJson(networkCategory, null, "LOW", "LOW"))
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        assertThat(get(location, loginOk(mvc, "requester@example.com").bearer()))
                .hasStatusOk();
        assertThat(get(location, loginOk(mvc, "netagent@example.com").bearer())).hasStatusOk();
        assertThat(get(location, loginOk(mvc, "admin@example.com").bearer())).hasStatusOk();

        // Not allowed: indistinguishable from a ticket that doesn't exist.
        assertNotFound(get(location, loginOk(mvc, "other@example.com").bearer()));
        assertNotFound(get(location, loginOk(mvc, "hwagent@example.com").bearer()));
        assertNotFound(get(
                "/api/tickets/" + UUID.randomUUID(),
                loginOk(mvc, "admin@example.com").bearer()));
        assertThat(mvc.get().uri(location)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void agentCanFollowATicketTheyRaisedInAnotherTeamsQueue() {
        String token = loginOk(mvc, "hwagent@example.com").bearer();

        String location = createIncident(token, incidentJson(networkCategory, null, "LOW", "LOW"))
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        assertThat(get(location, token)).hasStatusOk();
    }

    // ----- helpers ------------------------------------------------------------------------------

    private MvcTestResult createIncident(String token, String json) {
        return mvc.post()
                .uri("/api/incidents")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult createServiceRequest(String token, String json) {
        return mvc.post()
                .uri("/api/service-requests")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, token).exchange();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    private static String incidentJson(UUID categoryId, UUID subcategoryId, String impact, String urgency) {
        return """
                {"title": "Wi-Fi keeps dropping", "description": "Disconnects every few minutes on floor 3",
                 "categoryId": "%s", "subcategoryId": %s, "impact": "%s", "urgency": "%s",
                 "affectedService": "Office Wi-Fi"}
                """.formatted(categoryId, subcategoryId == null ? "null" : "\"" + subcategoryId + "\"", impact, urgency);
    }
}
