package com.ademola.esm.ticket.assignment;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.jayway.jsonpath.JsonPath;
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

/** Ownership changes through the HTTP API with real tokens. */
@IntegrationTest
class AssignmentApiIT {

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
    User agent;
    User colleague;
    User lead;
    User hardwareAgent;
    User requester;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        network = fixtures.team("Network Team");
        hardware = fixtures.team("Hardware Team");
        networkCategory = fixtures.category("NETWORK", "Network", null, network, "INCIDENT");
        requester = users.create("requester@example.com", Role.REQUESTER);
        agent = users.create("agent@example.com", Role.AGENT);
        colleague = users.create("colleague@example.com", Role.AGENT);
        lead = users.create("lead@example.com", Role.TEAM_LEAD);
        hardwareAgent = users.create("hw@example.com", Role.AGENT);
        users.create("admin@example.com", Role.ADMIN);
        for (User member : new User[] {agent, colleague, lead}) {
            fixtures.member(network, member.getId());
        }
        fixtures.member(hardware, hardwareAgent.getId());
    }

    @Test
    void agentTakesTicketFromTeamQueueWhichMovesItToAssignedAndIsAudited() {
        String id = newIncident();

        MvcTestResult taken = assign("agent@example.com", id, network, agent.getId(), 0);

        assertThat(taken).hasStatusOk();
        assertThat(taken).bodyJson().extractingPath("$.status").isEqualTo("ASSIGNED");
        assertThat(taken).bodyJson().extractingPath("$.assignee.name").isEqualTo("Agent");
        assertThat(taken).bodyJson().extractingPath("$.version").isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_id, old_value->>'status' as before, new_value->>'status' as after,"
                        + " new_value->>'assigneeId' as assignee from audit_events where action = 'TICKET_ASSIGNMENT_CHANGED'");
        assertThat(audit.get("actor_id")).isEqualTo(agent.getId());
        assertThat(audit)
                .containsEntry("before", "NEW")
                .containsEntry("after", "ASSIGNED")
                .containsEntry("assignee", agent.getId().toString());
    }

    @Test
    void agentCannotAssignToAColleagueButTheTeamLeadCan() {
        String id = newIncident();

        MvcTestResult byAgent = assign("agent@example.com", id, network, colleague.getId(), 0);
        assertThat(byAgent).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(byAgent).bodyJson().extractingPath("$.code").isEqualTo("ASSIGNMENT_NOT_PERMITTED");

        assertThat(assign("lead@example.com", id, network, colleague.getId(), 0))
                .hasStatusOk();
    }

    @Test
    void assigneeMustBeAnActiveStaffMemberOfTheTeam() {
        String id = newIncident();
        User inactive = users.create("gone@example.com", Role.AGENT);
        fixtures.member(network, inactive.getId());
        jdbc.update("update users set active = false where id = ?", inactive.getId());

        for (UUID ineligible : new UUID[] {hardwareAgent.getId(), requester.getId(), inactive.getId()}) {
            MvcTestResult result = assign("lead@example.com", id, network, ineligible, 0);
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ASSIGNEE_NOT_IN_TEAM");
        }
    }

    @Test
    void releasingReturnsTicketToTheQueue() {
        String id = newIncident();
        assign("agent@example.com", id, network, agent.getId(), 0);

        MvcTestResult released = assign("agent@example.com", id, network, null, 1);

        assertThat(released).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        assertThat(released).bodyJson().extractingPath("$.assignee").isNull();
    }

    @Test
    void transferringWorkInProgressSendsItUnassignedToTheOtherTeamAndOutOfSight() {
        String id = newIncident();
        assign("agent@example.com", id, network, agent.getId(), 0);
        startWork(id, 1);

        MvcTestResult transferred = assign("agent@example.com", id, hardware, null, 2);

        assertThat(transferred).hasStatusOk();
        assertThat(transferred).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        assertThat(transferred).bodyJson().extractingPath("$.assignedTeam.name").isEqualTo("Hardware Team");
        assertThat(get("agent@example.com", id)).hasStatus(HttpStatus.NOT_FOUND); // no longer their team's
        assertThat(get("hw@example.com", id)).hasStatusOk();
    }

    @Test
    void agentCannotChooseTheOwnerInTheReceivingTeamButAdminCan() {
        String id = newIncident();

        assertThat(assign("agent@example.com", id, hardware, hardwareAgent.getId(), 0))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(assign("admin@example.com", id, hardware, hardwareAgent.getId(), 0))
                .hasStatusOk();
    }

    @Test
    void cannotTransferToAnInactiveTeam() {
        String id = newIncident();
        jdbc.update("update teams set active = false where id = ?", hardware);

        assertThat(assign("agent@example.com", id, hardware, null, 0))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("TEAM_INACTIVE");
    }

    @Test
    void requestersCannotChangeOwnershipAndOutsidersCannotSeeTheTicket() {
        String id = newIncident();

        assertThat(assign("requester@example.com", id, network, null, 0)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(assign("hw@example.com", id, hardware, hardwareAgent.getId(), 0))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolvedTicketsCannotBeReassigned() {
        String id = newIncident();
        assign("agent@example.com", id, network, agent.getId(), 0);
        startWork(id, 1);
        transition("agent@example.com", id, 2, "RESOLVED", ", \"resolutionCode\": \"FIXED\", \"notes\": \"done\"");

        MvcTestResult result = assign("lead@example.com", id, network, colleague.getId(), 3);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TICKET_NOT_ASSIGNABLE");
    }

    @Test
    void repeatingTheCurrentAssignmentIsANoOp() {
        String id = newIncident();
        assign("agent@example.com", id, network, agent.getId(), 0);

        MvcTestResult again = assign("agent@example.com", id, network, agent.getId(), 1);

        assertThat(again).hasStatusOk();
        assertThat(again).bodyJson().extractingPath("$.version").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'TICKET_ASSIGNMENT_CHANGED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleVersionIsAConflict() {
        String id = newIncident();
        assign("agent@example.com", id, network, agent.getId(), 0);

        assertThat(assign("lead@example.com", id, network, colleague.getId(), 0))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void memberOwningOpenTicketsCannotBeRemovedOrDemotedUntilTheyAreClosed() {
        String id = newIncident();
        assign("agent@example.com", id, network, agent.getId(), 0);
        String admin = loginOk(mvc, "admin@example.com").bearer();

        MvcTestResult removal = mvc.delete()
                .uri("/api/admin/teams/{team}/members/{user}", network, agent.getId())
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange();
        assertThat(removal).hasStatus(HttpStatus.CONFLICT);
        assertThat(removal).bodyJson().extractingPath("$.code").isEqualTo("MEMBER_HAS_OPEN_TICKETS");

        MvcTestResult demotion = mvc.patch()
                .uri("/api/admin/users/{id}", agent.getId())
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"REQUESTER\", \"version\": 0}")
                .exchange();
        assertThat(demotion).hasStatus(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, agent.getId()))
                .as("the role change was rolled back with the failed membership removal")
                .isEqualTo("AGENT");

        jdbc.update("update work_items set status = 'CLOSED' where id = ?::uuid", id);
        assertThat(mvc.delete()
                        .uri("/api/admin/teams/{team}/members/{user}", network, agent.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    // ----- helpers ------------------------------------------------------------------------------

    private String newIncident() {
        MvcTestResult result = mvc.post()
                .uri("/api/incidents")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        loginOk(mvc, "requester@example.com").bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Wi-Fi down", "description": "Floor 3", "categoryId": "%s",
                         "impact": "LOW", "urgency": "LOW"}
                        """.formatted(networkCategory))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcTestResult assign(String email, String id, UUID teamId, UUID assigneeId, long version) {
        return mvc.put()
                .uri("/api/tickets/{id}/assignment", id)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"%s\", \"assigneeId\": %s, \"version\": %d}"
                        .formatted(teamId, assigneeId == null ? "null" : "\"" + assigneeId + "\"", version))
                .exchange();
    }

    private void startWork(String id, long version) {
        assertThat(transition("agent@example.com", id, version, "IN_PROGRESS", ""))
                .hasStatusOk();
    }

    private MvcTestResult transition(String email, String id, long version, String target, String extra) {
        return mvc.post()
                .uri("/api/tickets/{id}/transitions", id)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\": \"%s\", \"version\": %d%s}".formatted(target, version, extra))
                .exchange();
    }

    private MvcTestResult get(String email, String id) {
        return mvc.get()
                .uri("/api/tickets/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, loginOk(mvc, email).bearer())
                .exchange();
    }
}
