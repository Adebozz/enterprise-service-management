package com.ademola.esm.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class TeamApiIT {

    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    TeamRepository teams;

    @BeforeEach
    void clean() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void adminCreatesTeamAndDuplicateNameInAnyCaseIsRejected() {
        MvcTestResult created = postJson("/api/admin/teams", """
                {"name": "Network Team", "description": "LAN, WAN and Wi-Fi"}
                """);
        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Network Team");

        MvcTestResult duplicate = postJson("/api/admin/teams", """
                {"name": "network TEAM"}
                """);
        assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
        assertThat(duplicate).bodyJson().extractingPath("$.code").isEqualTo("TEAM_NAME_ALREADY_EXISTS");
    }

    @Test
    void renamingToAnotherTeamsNameIsRejected() {
        teams.save(new Team("Network Team", null));
        Team hardware = teams.save(new Team("Hardware Team", null));

        MvcTestResult result = mvc.patch()
                .uri("/api/admin/teams/" + hardware.getId())
                .with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "NETWORK TEAM", "version": 0}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void membershipIsManagedIdempotentlyAndListedWithUserDetails() {
        Team team = teams.save(new Team("Network Team", null));
        User agent = users.save(new User("agent@example.com", "Zoe Agent", "h", Role.AGENT));
        User lead = users.save(new User("lead@example.com", "Amir Lead", "h", Role.TEAM_LEAD));
        String members = "/api/admin/teams/" + team.getId() + "/members/";

        assertThat(mvc.put().uri(members + agent.getId()).with(ADMIN)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.put().uri(members + agent.getId()).with(ADMIN)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.put().uri(members + lead.getId()).with(ADMIN)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult list = mvc.get()
                .uri("/api/teams/" + team.getId() + "/members")
                .with(user("agent").roles("AGENT"))
                .exchange();
        assertThat(list).hasStatusOk();
        assertThat(list)
                .bodyJson()
                .extractingPath("$[*].displayName")
                .asArray()
                .containsExactly("Amir Lead", "Zoe Agent");

        assertThat(mvc.delete().uri(members + agent.getId()).with(ADMIN)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.delete().uri(members + agent.getId()).with(ADMIN)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(membershipCount(team.getId())).isEqualTo(1);
    }

    @Test
    void requesterCannotBeAddedToATeam() {
        Team team = teams.save(new Team("Network Team", null));
        User requester = users.save(new User("req@example.com", "Req", "h", Role.REQUESTER));

        MvcTestResult result = mvc.put()
                .uri("/api/admin/teams/" + team.getId() + "/members/" + requester.getId())
                .with(ADMIN)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("USER_NOT_ELIGIBLE_FOR_TEAM");
    }

    @Test
    void demotingAnAgentToRequesterRemovesTheirMembershipsInTheSameTransaction() {
        Team network = teams.save(new Team("Network Team", null));
        Team hardware = teams.save(new Team("Hardware Team", null));
        User agent = users.save(new User("agent@example.com", "Agent", "h", Role.AGENT));
        mvc.put()
                .uri("/api/admin/teams/" + network.getId() + "/members/" + agent.getId())
                .with(ADMIN)
                .exchange();
        mvc.put()
                .uri("/api/admin/teams/" + hardware.getId() + "/members/" + agent.getId())
                .with(ADMIN)
                .exchange();

        MvcTestResult demoted = mvc.patch()
                .uri("/api/admin/users/" + agent.getId())
                .with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"role": "REQUESTER", "version": 0}
                        """)
                .exchange();

        assertThat(demoted).hasStatusOk();
        assertThat(jdbc.queryForObject(
                        "select count(*) from team_members where user_id = ?", Integer.class, agent.getId()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENT", "TEAM_LEAD", "ADMIN"})
    void supportStaffCanListTeamsThanksToTheRoleHierarchy(String role) {
        teams.save(new Team("Network Team", null));
        Team inactive = teams.save(new Team("Old Team", null));
        inactive.deactivate();
        teams.save(inactive);

        MvcTestResult result =
                mvc.get().uri("/api/teams").with(user("u").roles(role)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[*].name").asArray().containsExactly("Network Team");
    }

    @Test
    void requestersCannotSeeTeams() {
        assertThat(mvc.get().uri("/api/teams").with(user("u").roles("REQUESTER")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void teamLeadsCannotAdministerTeams() {
        assertThat(mvc.post()
                        .uri("/api/admin/teams")
                        .with(user("lead").roles("TEAM_LEAD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Rogue Team\"}"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedCallersGet401() {
        assertThat(mvc.get().uri("/api/teams")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult postJson(String path, String json) {
        return mvc.post()
                .uri(path)
                .with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private int membershipCount(UUID teamId) {
        return jdbc.queryForObject("select count(*) from team_members where team_id = ?", Integer.class, teamId);
    }
}
