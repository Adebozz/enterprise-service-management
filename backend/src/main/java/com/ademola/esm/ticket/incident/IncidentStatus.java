package com.ademola.esm.ticket.incident;

/** Incident lifecycle. Allowed transitions are defined by the workflow engine (M4). */
public enum IncidentStatus {
    NEW,
    ASSIGNED,
    IN_PROGRESS,
    WAITING_FOR_USER,
    RESOLVED,
    CLOSED,
    CANCELLED
}
