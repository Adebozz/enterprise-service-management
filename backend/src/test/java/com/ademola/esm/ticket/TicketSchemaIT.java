package com.ademola.esm.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database-level guarantees of V4, tested with raw SQL that bypasses the application. */
@IntegrationTest
class TicketSchemaIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    @Autowired
    TestUsers users;

    UUID team;
    UUID category;
    UUID requester;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        team = fixtures.team("Network Team");
        category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        requester = users.create("req@example.com", Role.REQUESTER).getId();
    }

    @Test
    void statusMustBelongToTheTicketType() {
        assertThatThrownBy(() -> insertWorkItem("INC-900001", "INCIDENT", "FULFILLED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("work_items_status_check");
        assertThatThrownBy(() -> insertWorkItem("REQ-900001", "SERVICE_REQUEST", "RESOLVED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("work_items_status_check");
    }

    @Test
    void referenceMustBeUniqueAndWellFormed() {
        insertWorkItem("INC-900001", "INCIDENT", "NEW", null);

        assertThatThrownBy(() -> insertWorkItem("INC-900001", "INCIDENT", "NEW", null))
                .hasMessageContaining("work_items_reference_uk");
        assertThatThrownBy(() -> insertWorkItem("INC-12", "INCIDENT", "NEW", null))
                .hasMessageContaining("work_items_reference_format_check");
    }

    @Test
    void assigneeMustBeAMemberOfTheAssignedTeamWhenAssigned() {
        UUID agent = users.create("agent@example.com", Role.AGENT).getId();

        assertThatThrownBy(() -> insertWorkItem("INC-900002", "INCIDENT", "ASSIGNED", agent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("is not a member of team");

        fixtures.member(team, agent);
        insertWorkItem("INC-900003", "INCIDENT", "ASSIGNED", agent);
    }

    @Test
    void memberWithOpenTicketsCannotLeaveButClosedHistoryDoesNotBlockRemoval() {
        UUID agent = users.create("agent@example.com", Role.AGENT).getId();
        fixtures.member(team, agent);
        insertWorkItem("INC-900004", "INCIDENT", "IN_PROGRESS", agent);

        assertThatThrownBy(() -> jdbc.update("delete from team_members where user_id = ?", agent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("still owns open tickets");

        jdbc.update("update work_items set status = 'CLOSED' where reference = 'INC-900004'");
        jdbc.update("delete from team_members where user_id = ?", agent);
        assertThat(jdbc.queryForObject("select assignee_id from work_items where reference = 'INC-900004'", UUID.class))
                .as("history keeps the original assignee")
                .isEqualTo(agent);
    }

    @Test
    void topLevelCategoryMustRouteToATeam() {
        assertThatThrownBy(() -> fixtures.category("ORPHAN", "Orphan", null, null, "INCIDENT"))
                .hasMessageContaining("categories_top_level_team_check");
    }

    @Test
    void auditTrailIsAppendOnly() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into audit_events (id, occurred_at, action, entity_type, entity_id) values (?, now(), 'TICKET_CREATED', 'WORK_ITEM', ?)",
                id,
                UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("update audit_events set action = 'TAMPERED' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("delete from audit_events where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("append-only: DELETE is not allowed");
        assertThat(jdbc.queryForObject("select action from audit_events where id = ?", String.class, id))
                .isEqualTo("TICKET_CREATED");
    }

    private void insertWorkItem(String reference, String type, String status, UUID assignee) {
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, requester_id, assigned_team_id, assignee_id, created_at, updated_at)"
                        + " values (?, ?, ?, 'T', 'D', ?, 'LOW', 'LOW', 'P4', ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(),
                reference,
                type,
                status,
                category,
                requester,
                team,
                assignee);
    }
}
