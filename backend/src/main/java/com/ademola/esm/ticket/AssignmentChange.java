package com.ademola.esm.ticket;

import java.util.UUID;

/** Ownership and status before and after an assignment, for auditing and the API response. */
public record AssignmentChange(Snapshot before, Snapshot after) {

    public record Snapshot(UUID teamId, UUID assigneeId, String status) {}

    public boolean teamChanged() {
        return !before.teamId().equals(after.teamId());
    }
}
