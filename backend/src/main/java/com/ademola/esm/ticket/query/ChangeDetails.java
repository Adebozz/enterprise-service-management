package com.ademola.esm.ticket.query;

import com.ademola.esm.ticket.change.ChangeOutcome;
import com.ademola.esm.ticket.change.ChangeRisk;
import com.ademola.esm.ticket.change.ChangeType;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * A change's plan, its review, the problem it fixes, and the other scheduled changes whose windows
 * overlap its own ({@code clashes}), so whoever plans or approves it can see the collision.
 */
public record ChangeDetails(
        ChangeType changeType,
        ChangeRisk risk,
        @Nullable String implementationPlan,
        @Nullable String backoutPlan,
        @Nullable Instant plannedStart,
        @Nullable Instant plannedEnd,
        @Nullable Instant implementedAt,
        @Nullable ChangeOutcome outcome,
        @Nullable String reviewNotes,
        @Nullable LinkedTicket problem,
        List<LinkedTicket> clashes) {}
