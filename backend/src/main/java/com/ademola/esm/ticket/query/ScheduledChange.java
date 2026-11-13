package com.ademola.esm.ticket.query;

import com.ademola.esm.ticket.change.ChangeRisk;
import com.ademola.esm.ticket.change.ChangeType;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/** One entry on the change schedule. */
public record ScheduledChange(
        UUID id,
        String reference,
        String title,
        String status,
        ChangeType changeType,
        ChangeRisk risk,
        Instant plannedStart,
        Instant plannedEnd,
        NamedRef team,
        @Nullable NamedRef assignee) {}
