package com.ademola.esm.ticket.query;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * What a problem's investigation found, the incidents it explains, and the changes raised to fix it
 * (each oldest first).
 */
public record ProblemDetails(
        @Nullable String rootCause,
        @Nullable String workaround,
        @Nullable Instant knownErrorAt,
        @Nullable String fixNotes,
        int reopenCount,
        List<LinkedTicket> incidents,
        List<LinkedTicket> changes) {}
