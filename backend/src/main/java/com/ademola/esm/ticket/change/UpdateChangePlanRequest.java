package com.ademola.esm.ticket.change;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** The whole plan as it should now read; a blank or missing field clears it. */
public record UpdateChangePlanRequest(
        @NotNull ChangeType changeType,
        @NotNull ChangeRisk risk,
        @Nullable @Size(max = 10_000) String implementationPlan,
        @Nullable @Size(max = 10_000) String backoutPlan,
        @Nullable Instant plannedStart,
        @Nullable Instant plannedEnd,
        @NotNull Long version) {

    ChangePlan plan() {
        return new ChangePlan(changeType, risk, implementationPlan, backoutPlan, plannedStart, plannedEnd);
    }
}
