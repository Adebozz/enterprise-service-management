package com.ademola.esm.ticket.assignment;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The desired ownership. {@code assigneeId} null means "unassigned in {@code teamId}". Sending the
 * current values is a no-op.
 */
public record AssignmentRequest(
        @NotNull UUID teamId, UUID assigneeId, @NotNull Long version) {}
