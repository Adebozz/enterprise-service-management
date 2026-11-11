package com.ademola.esm.ticket.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.ticket.workflow.InvalidTransitionException;
import com.ademola.esm.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Moves other modules make on a ticket's behalf (approvals, for example), outside the HTTP API. */
@IntegrationTest
class SystemTransitionIT {

    @Autowired
    TicketTransitionService transitions;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    @Autowired
    TestUsers users;

    UUID ticketId;
    UUID requesterId;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        UUID team = fixtures.team("Identity & Access");
        UUID category = fixtures.category("ACCESS", "Access", null, team, "SERVICE_REQUEST");
        requesterId = users.create("req@example.com", Role.REQUESTER).getId();
        ticketId = UUID.randomUUID();
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, requester_id, assigned_team_id, created_at, updated_at, version)"
                        + " values (?, 'REQ-' || lpad(nextval('service_request_ref_seq')::text, 6, '0'),"
                        + " 'SERVICE_REQUEST', 't', 'd', 'SUBMITTED', 'LOW', 'MEDIUM', 'P4', ?, ?, ?, now(), now(), 0)",
                ticketId,
                category,
                requesterId,
                team);
        jdbc.update("insert into service_requests (work_item_id) values (?)", ticketId);
    }

    @Test
    void appliesTheMoveAndAuditsItsTriggerWithoutABlankNote() {
        apply("APPROVAL_PENDING", "APPROVAL_REQUIRED", "   ");

        assertThat(status()).isEqualTo("APPROVAL_PENDING");
        assertThat(jdbc.queryForObject(
                        "select metadata->>'trigger' from audit_events where action = 'TICKET_STATUS_CHANGED'",
                        String.class))
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from comments", Integer.class))
                .isZero();
    }

    @Test
    void postsANoteAsAPublicCommentExplainingTheNewStatus() {
        apply("APPROVAL_PENDING", "APPROVAL_REQUIRED", null);
        apply("REJECTED", "APPROVAL", " Too broad ");

        assertThat(status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForMap("select body, visibility, related_status from comments"))
                .containsEntry("body", "Too broad")
                .containsEntry("visibility", "PUBLIC")
                .containsEntry("related_status", "REJECTED");
    }

    @Test
    void refusesMovesThatBelongToUsers() {
        assertThatThrownBy(() -> apply("CANCELLED", "TEST", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a system transition");
        assertThat(status()).isEqualTo("SUBMITTED");
    }

    @Test
    void refusesMovesOutsideTheLifecycleAndUnknownTickets() {
        assertThatThrownBy(() -> apply("FULFILLED", "TEST", null)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        tx -> transitions.applySystemTransition(UUID.randomUUID(), "APPROVED", "TEST", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void apply(String target, String trigger, String note) {
        transaction.executeWithoutResult(
                tx -> transitions.applySystemTransition(ticketId, target, trigger, requesterId, note));
    }

    private String status() {
        return jdbc.queryForObject("select status from work_items where id = ?", String.class, ticketId);
    }
}
