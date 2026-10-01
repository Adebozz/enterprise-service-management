package com.ademola.esm.ticket.incident;

import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Urgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotNull UUID categoryId,
        UUID subcategoryId,
        @NotNull Impact impact,
        @NotNull Urgency urgency,
        @Size(max = 120) String affectedService) {}
