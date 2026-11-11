package com.ademola.esm.sla;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.incident.IncidentStatus;
import com.ademola.esm.ticket.request.ServiceRequestStatus;

/**
 * What a ticket status means for its SLA clocks. The switches are exhaustive, so a new status
 * doesn't compile until someone decides how it affects service levels.
 */
enum SlaPhase {
    /** Support should be working on it: clocks run. */
    ACTIVE,
    /** Waiting on someone outside support (the requester, an approver): clocks pause. */
    WAITING,
    /** The work is done: clocks stop. Reopening makes the ticket active again. */
    DONE,
    /** Cancelled or rejected: clocks no longer count. */
    CANCELLED;

    static SlaPhase of(WorkItemType type, String status) {
        return switch (type) {
            case INCIDENT ->
                switch (IncidentStatus.valueOf(status)) {
                    case NEW, ASSIGNED, IN_PROGRESS -> ACTIVE;
                    case WAITING_FOR_USER -> WAITING;
                    case RESOLVED, CLOSED -> DONE;
                    case CANCELLED -> CANCELLED;
                };
            case SERVICE_REQUEST ->
                switch (ServiceRequestStatus.valueOf(status)) {
                    case SUBMITTED, APPROVED, IN_PROGRESS -> ACTIVE;
                    case APPROVAL_PENDING -> WAITING;
                    case FULFILLED, CLOSED -> DONE;
                    case CANCELLED, REJECTED -> CANCELLED;
                };
            case PROBLEM -> throw new IllegalArgumentException("Problems have no SLA clocks");
        };
    }
}
