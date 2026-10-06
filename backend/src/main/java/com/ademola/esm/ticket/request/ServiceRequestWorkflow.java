package com.ademola.esm.ticket.request;

import static com.ademola.esm.ticket.request.ServiceRequestStatus.APPROVAL_PENDING;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.APPROVED;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.CANCELLED;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.CLOSED;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.FULFILLED;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.IN_PROGRESS;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.REJECTED;
import static com.ademola.esm.ticket.request.ServiceRequestStatus.SUBMITTED;
import static com.ademola.esm.ticket.workflow.Actor.ADMIN;
import static com.ademola.esm.ticket.workflow.Actor.REQUESTER;
import static com.ademola.esm.ticket.workflow.Actor.SUPPORT;
import static com.ademola.esm.ticket.workflow.Actor.SYSTEM;
import static com.ademola.esm.ticket.workflow.Requirement.ASSIGNEE;
import static com.ademola.esm.ticket.workflow.Requirement.FULFILMENT_NOTES;
import static com.ademola.esm.ticket.workflow.Requirement.REASON;

import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Set;

/**
 * The service-request lifecycle.
 *
 * <pre>
 * SUBMITTED --start fulfilment--> IN_PROGRESS --fulfil--> FULFILLED --confirm & close--> CLOSED
 *     \--(system, Phase 2)--> APPROVAL_PENDING --> APPROVED --start fulfilment--^     |
 *                                             \--> REJECTED            reopen <------/
 * SUBMITTED / APPROVAL_PENDING / APPROVED / IN_PROGRESS --cancel--> CANCELLED
 * </pre>
 *
 * Approval moves are SYSTEM-only: the approval engine (Phase 2) drives them, and no user can
 * approve a request by calling the transitions endpoint.
 */
public final class ServiceRequestWorkflow {

    public static final WorkflowDefinition<ServiceRequestStatus> DEFINITION = WorkflowDefinition.builder(
                    "Service request", ServiceRequestStatus.class)
            .allow(SUBMITTED, IN_PROGRESS, "Start fulfilment", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(SUBMITTED, APPROVAL_PENDING, "Request approval", Set.of(SYSTEM))
            .allow(APPROVAL_PENDING, APPROVED, "Approve", Set.of(SYSTEM))
            .allow(APPROVAL_PENDING, REJECTED, "Reject", Set.of(SYSTEM))
            .allow(APPROVED, IN_PROGRESS, "Start fulfilment", Set.of(SUPPORT), Set.of(ASSIGNEE))
            .allow(IN_PROGRESS, FULFILLED, "Fulfil", Set.of(SUPPORT), Set.of(FULFILMENT_NOTES))
            .allow(IN_PROGRESS, SUBMITTED, "Return to queue", Set.of(SYSTEM))
            .allow(FULFILLED, CLOSED, "Confirm and close", Set.of(REQUESTER, ADMIN, SYSTEM))
            .allow(FULFILLED, IN_PROGRESS, "Reopen", Set.of(REQUESTER, SUPPORT), Set.of(REASON))
            .allowFromEach(
                    List.of(SUBMITTED, APPROVAL_PENDING, APPROVED, IN_PROGRESS),
                    CANCELLED,
                    "Cancel request",
                    Set.of(REQUESTER, SUPPORT),
                    Set.of(REASON))
            .build();

    private ServiceRequestWorkflow() {}
}
