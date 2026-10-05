package com.ademola.esm.ticket.transition;

import com.ademola.esm.ticket.incident.ResolutionCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to move a ticket to another status. Which optional fields are required depends on the
 * transition (see {@code requirements} in {@link AvailableTransition}).
 */
public record TransitionRequest(
        @NotBlank String targetStatus,
        @NotNull Long version,
        @Size(max = 1000) String reason,
        // A typed enum (not free text), so the contract lists the valid codes for clients.
        ResolutionCode resolutionCode,
        @Size(max = 10_000) String notes) {}
