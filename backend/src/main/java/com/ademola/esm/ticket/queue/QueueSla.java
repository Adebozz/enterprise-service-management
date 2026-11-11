package com.ademola.esm.ticket.queue;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * The SLA target a ticket is currently working towards, for queue badges: the response target until
 * support responds, then the resolution target. Absent when no target is open (done, cancelled, or
 * a ticket from before the SLA engine).
 *
 * <p>Computed by the queue's SQL from {@code sla_clocks}. The same rule is implemented in Java by
 * {@code SlaClock.status}; {@code SlaApiIT} checks the two agree.
 *
 * @param dueAt when the target is due; {@code null} while paused
 */
public record QueueSla(
        Metric metric, Status status, @Nullable Instant dueAt) {

    public enum Metric {
        RESPONSE,
        RESOLUTION
    }

    public enum Status {
        ON_TRACK,
        AT_RISK,
        BREACHED,
        PAUSED
    }
}
