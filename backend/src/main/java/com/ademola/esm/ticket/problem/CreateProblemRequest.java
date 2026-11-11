package com.ademola.esm.ticket.problem;

import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Urgency;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Raises a problem, optionally starting from the incident that prompted it, which is then linked
 * straight away.
 */
public record CreateProblemRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotNull UUID categoryId,
        @Nullable UUID subcategoryId,
        @NotNull Impact impact,
        @NotNull Urgency urgency,
        @Nullable UUID incidentId) {}
