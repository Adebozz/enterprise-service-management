package com.ademola.esm.sla;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * A ticket's SLA clocks as support sees them.
 *
 * @param clocks response first, then resolution; empty for tickets raised before the SLA engine
 */
record TicketSlaResponse(List<SlaClockView> clocks) {

    /**
     * @param dueAt the deadline while running; {@code null} when paused or finished
     * @param elapsedSeconds time counted so far
     * @param remainingSeconds time left before the target; negative once overdue
     */
    record SlaClockView(
            SlaMetric metric,
            SlaStatus status,
            int targetMinutes,
            long elapsedSeconds,
            long remainingSeconds,
            @Nullable Instant dueAt,
            @Nullable Instant breachedAt,
            @Nullable Instant completedAt) {}
}
