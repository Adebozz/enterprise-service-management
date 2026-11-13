package com.ademola.esm.ticket.change;

import static com.ademola.esm.ticket.change.ChangeStatus.APPROVAL_PENDING;
import static com.ademola.esm.ticket.change.ChangeStatus.APPROVED;
import static com.ademola.esm.ticket.change.ChangeStatus.ASSIGNED;
import static com.ademola.esm.ticket.change.ChangeStatus.CANCELLED;
import static com.ademola.esm.ticket.change.ChangeStatus.CLOSED;
import static com.ademola.esm.ticket.change.ChangeStatus.IMPLEMENTING;
import static com.ademola.esm.ticket.change.ChangeStatus.NEW;
import static com.ademola.esm.ticket.change.ChangeStatus.REJECTED;
import static com.ademola.esm.ticket.change.ChangeStatus.REVIEW;
import static com.ademola.esm.ticket.workflow.Actor.ADMIN;
import static com.ademola.esm.ticket.workflow.Actor.SUPPORT;
import static com.ademola.esm.ticket.workflow.Actor.SYSTEM;
import static com.ademola.esm.ticket.workflow.Requirement.ASSIGNEE;
import static com.ademola.esm.ticket.workflow.Requirement.REASON;

import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Set;

/**
 * The change lifecycle.
 *
 * <pre>
 * NEW --assign (system)--> ASSIGNED --submit--> APPROVAL_PENDING --approve (system)--> APPROVED
 *                             |                        '--reject (system)--> REJECTED
 *                             '--schedule standard change-------------------> APPROVED
 *
 * APPROVED --start implementation--> IMPLEMENTING --finish--> REVIEW --close--> CLOSED
 * NEW / ASSIGNED / APPROVAL_PENDING / APPROVED --cancel--> CANCELLED
 * </pre>
 *
 * Approval decisions are SYSTEM moves made by the approvals module, so nobody approves a change
 * through the ordinary transitions endpoint. Which of the two ways out of ASSIGNED applies depends
 * on the change type, and each needs a complete plan; {@link Change} checks both, since they are
 * ticket state rather than input. Closing needs the review's outcome.
 */
public final class ChangeWorkflow {

    public static final WorkflowDefinition<ChangeStatus> DEFINITION = WorkflowDefinition.builder(
                    "Change", ChangeStatus.class)
            .allow(NEW, ASSIGNED, "Assign", Set.of(SYSTEM))
            .allow(ASSIGNED, NEW, "Unassign", Set.of(SYSTEM))
            .allow(ASSIGNED, APPROVAL_PENDING, "Submit for approval", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(ASSIGNED, APPROVED, "Schedule standard change", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(APPROVAL_PENDING, APPROVED, "Approve", Set.of(SYSTEM))
            .allow(APPROVAL_PENDING, REJECTED, "Reject", Set.of(SYSTEM))
            .allow(APPROVED, IMPLEMENTING, "Start implementation", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(IMPLEMENTING, REVIEW, "Finish implementation", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(REVIEW, CLOSED, "Close", Set.of(SUPPORT, ADMIN))
            .allowFromEach(
                    List.of(NEW, ASSIGNED, APPROVAL_PENDING, APPROVED),
                    CANCELLED,
                    "Cancel change",
                    Set.of(SUPPORT),
                    Set.of(REASON))
            .build();

    private ChangeWorkflow() {}
}
