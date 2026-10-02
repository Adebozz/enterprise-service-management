package com.ademola.esm.ticket.incident;

import static com.ademola.esm.ticket.incident.IncidentStatus.ASSIGNED;
import static com.ademola.esm.ticket.incident.IncidentStatus.CANCELLED;
import static com.ademola.esm.ticket.incident.IncidentStatus.CLOSED;
import static com.ademola.esm.ticket.incident.IncidentStatus.IN_PROGRESS;
import static com.ademola.esm.ticket.incident.IncidentStatus.NEW;
import static com.ademola.esm.ticket.incident.IncidentStatus.RESOLVED;
import static com.ademola.esm.ticket.incident.IncidentStatus.WAITING_FOR_USER;
import static com.ademola.esm.ticket.workflow.Actor.ADMIN;
import static com.ademola.esm.ticket.workflow.Actor.REQUESTER;
import static com.ademola.esm.ticket.workflow.Actor.SUPPORT;
import static com.ademola.esm.ticket.workflow.Actor.SYSTEM;
import static com.ademola.esm.ticket.workflow.Requirement.ASSIGNEE;
import static com.ademola.esm.ticket.workflow.Requirement.REASON;
import static com.ademola.esm.ticket.workflow.Requirement.RESOLUTION;

import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Set;

/**
 * The incident lifecycle: every allowed status change, who may make it and what it needs.
 *
 * <pre>
 * NEW --assign (system)--> ASSIGNED --start work--> IN_PROGRESS <--resume-- WAITING_FOR_USER
 *   ^-- unassign / return to queue (system, when the owner is removed) --'
 *                                                   |  ^   \--wait for user--/
 *                                           resolve |  | reopen
 *                                                   v  |
 *                                                 RESOLVED --confirm & close--> CLOSED
 * NEW / ASSIGNED / IN_PROGRESS / WAITING_FOR_USER --cancel--> CANCELLED
 * </pre>
 */
public final class IncidentWorkflow {

    public static final WorkflowDefinition<IncidentStatus> DEFINITION = WorkflowDefinition.builder(
                    "Incident", IncidentStatus.class)
            // Driven by assignment (M5): giving the ticket an owner moves it to ASSIGNED and back.
            .allow(NEW, ASSIGNED, "Assign", Set.of(SYSTEM))
            .allow(ASSIGNED, NEW, "Unassign", Set.of(SYSTEM))
            // Released or transferred without a new owner: work in progress always has an owner.
            .allow(IN_PROGRESS, NEW, "Return to queue", Set.of(SYSTEM))
            .allow(WAITING_FOR_USER, NEW, "Return to queue", Set.of(SYSTEM))
            .allow(ASSIGNED, IN_PROGRESS, "Start work", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(IN_PROGRESS, WAITING_FOR_USER, "Wait for user", Set.of(SUPPORT), Set.of(REASON))
            .allow(WAITING_FOR_USER, IN_PROGRESS, "Resume", Set.of(SUPPORT, REQUESTER))
            .allow(IN_PROGRESS, RESOLVED, "Resolve", Set.of(SUPPORT), Set.of(RESOLUTION))
            .allow(RESOLVED, CLOSED, "Confirm and close", Set.of(REQUESTER, ADMIN, SYSTEM))
            .allow(RESOLVED, IN_PROGRESS, "Reopen", Set.of(REQUESTER, SUPPORT), Set.of(REASON))
            .allowFromEach(
                    List.of(NEW, ASSIGNED, IN_PROGRESS, WAITING_FOR_USER),
                    CANCELLED,
                    "Cancel",
                    Set.of(REQUESTER, SUPPORT),
                    Set.of(REASON))
            .build();

    private IncidentWorkflow() {}
}
