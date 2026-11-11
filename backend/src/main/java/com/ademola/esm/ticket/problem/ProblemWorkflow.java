package com.ademola.esm.ticket.problem;

import static com.ademola.esm.ticket.problem.ProblemStatus.ASSIGNED;
import static com.ademola.esm.ticket.problem.ProblemStatus.CANCELLED;
import static com.ademola.esm.ticket.problem.ProblemStatus.CLOSED;
import static com.ademola.esm.ticket.problem.ProblemStatus.INVESTIGATING;
import static com.ademola.esm.ticket.problem.ProblemStatus.KNOWN_ERROR;
import static com.ademola.esm.ticket.problem.ProblemStatus.NEW;
import static com.ademola.esm.ticket.problem.ProblemStatus.RESOLVED;
import static com.ademola.esm.ticket.workflow.Actor.ADMIN;
import static com.ademola.esm.ticket.workflow.Actor.SUPPORT;
import static com.ademola.esm.ticket.workflow.Actor.SYSTEM;
import static com.ademola.esm.ticket.workflow.Requirement.ASSIGNEE;
import static com.ademola.esm.ticket.workflow.Requirement.FIX_NOTES;
import static com.ademola.esm.ticket.workflow.Requirement.REASON;

import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Set;

/**
 * The problem lifecycle. Only support moves a problem along: there is no requester waiting on it,
 * so there is no "waiting for user" and no confirmation step.
 *
 * <pre>
 * NEW --assign (system)--> ASSIGNED --start investigation--> INVESTIGATING --known error--> KNOWN_ERROR
 *                                                                 |  ^                          |
 *                                                         resolve |  | reopen          resolve  |
 *                                                                 v  |                          |
 *                                                               RESOLVED <----------------------'
 *                                                                 |
 *                                                                 '--close--> CLOSED
 * NEW / ASSIGNED / INVESTIGATING / KNOWN_ERROR --cancel--> CANCELLED
 * </pre>
 *
 * Becoming a known error and resolving also need the analysis (root cause, and for a known error a
 * workaround) recorded beforehand; {@link Problem} checks that, since it is ticket state, not input.
 */
public final class ProblemWorkflow {

    public static final WorkflowDefinition<ProblemStatus> DEFINITION = WorkflowDefinition.builder(
                    "Problem", ProblemStatus.class)
            .allow(NEW, ASSIGNED, "Assign", Set.of(SYSTEM))
            .allow(ASSIGNED, NEW, "Unassign", Set.of(SYSTEM))
            .allow(INVESTIGATING, NEW, "Return to queue", Set.of(SYSTEM))
            .allow(KNOWN_ERROR, NEW, "Return to queue", Set.of(SYSTEM))
            .allow(ASSIGNED, INVESTIGATING, "Start investigation", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(INVESTIGATING, KNOWN_ERROR, "Record known error", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(INVESTIGATING, RESOLVED, "Resolve", Set.of(SUPPORT), Set.of(ASSIGNEE, FIX_NOTES))
            .allow(KNOWN_ERROR, RESOLVED, "Resolve", Set.of(SUPPORT), Set.of(ASSIGNEE, FIX_NOTES))
            .allow(RESOLVED, INVESTIGATING, "Reopen", Set.of(SUPPORT), Set.of(REASON))
            .allow(RESOLVED, CLOSED, "Close", Set.of(SUPPORT, ADMIN))
            .allowFromEach(
                    List.of(NEW, ASSIGNED, INVESTIGATING, KNOWN_ERROR),
                    CANCELLED,
                    "Cancel problem",
                    Set.of(SUPPORT),
                    Set.of(REASON))
            .build();

    private ProblemWorkflow() {}
}
