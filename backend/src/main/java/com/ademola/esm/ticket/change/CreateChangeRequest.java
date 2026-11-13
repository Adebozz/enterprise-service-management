package com.ademola.esm.ticket.change;

import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Urgency;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Raises a change. The plan may be filled in now or later, but must be complete before the change
 * is submitted. {@code problemId} names the problem this change permanently fixes.
 */
public record CreateChangeRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotNull UUID categoryId,
        @Nullable UUID subcategoryId,
        @NotNull Impact impact,
        @NotNull Urgency urgency,
        @NotNull ChangeType changeType,
        @NotNull ChangeRisk risk,
        @Nullable @Size(max = 10_000) String implementationPlan,
        @Nullable @Size(max = 10_000) String backoutPlan,
        @Nullable Instant plannedStart,
        @Nullable Instant plannedEnd,
        @Nullable UUID problemId) {

    ChangePlan plan() {
        return new ChangePlan(changeType, risk, implementationPlan, backoutPlan, plannedStart, plannedEnd);
    }
}
