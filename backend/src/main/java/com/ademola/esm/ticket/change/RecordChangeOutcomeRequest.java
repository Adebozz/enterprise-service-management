package com.ademola.esm.ticket.change;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The review: how the change went, and what was learned. */
public record RecordChangeOutcomeRequest(
        @NotNull ChangeOutcome outcome,
        @NotBlank @Size(max = 10_000) String notes,
        @NotNull Long version) {}
