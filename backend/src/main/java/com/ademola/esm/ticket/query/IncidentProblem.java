package com.ademola.esm.ticket.query;

import jakarta.annotation.Nullable;
import java.util.UUID;

/** The problem behind an incident, with its workaround so support can apply it from the incident. */
public record IncidentProblem(
        UUID id,
        String reference,
        String title,
        String status,
        @Nullable String workaround) {}
