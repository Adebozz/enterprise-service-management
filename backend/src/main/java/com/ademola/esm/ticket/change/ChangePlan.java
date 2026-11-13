package com.ademola.esm.ticket.change;

import jakarta.annotation.Nullable;
import java.time.Instant;

/** What a change will do, how it can be undone, and when. Parts may be missing while it is drafted. */
public record ChangePlan(
        ChangeType changeType,
        ChangeRisk risk,
        @Nullable String implementationPlan,
        @Nullable String backoutPlan,
        @Nullable Instant plannedStart,
        @Nullable Instant plannedEnd) {}
