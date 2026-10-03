package com.ademola.esm.ticket.query;

import jakarta.annotation.Nullable;

public record IncidentDetails(
        @Nullable String affectedService,
        @Nullable String resolutionCode,
        @Nullable String resolutionNotes,
        int reopenCount) {}
