package com.ademola.esm.sla;

import com.ademola.esm.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One SLA measurement (response or resolution) of one ticket.
 *
 * <p><b>How time is counted:</b> {@code elapsedMs} holds the time from finished running stretches;
 * while the clock runs, {@code runningSince} marks the start of the current stretch. Pausing folds
 * the stretch into {@code elapsedMs}, resuming starts a new one. So a paused clock keeps its
 * remaining time, and a reopened ticket continues from where it stopped rather than starting again.
 *
 * <p><b>Breaches are recorded, not only computed</b> ({@code breachedAt}). A breach stays on record
 * even if the ticket is later paused, completed or given a longer target, which is what reporting
 * needs. The time is the moment the deadline passed, wherever that's known.
 *
 * <p><b>Business hours:</b> a clock started under a business-hours policy counts only the desk's
 * working time ({@link BusinessCalendar}); {@code elapsedMs} is then working time, and {@code dueAt}
 * the instant the remaining working time runs out. The calendar lives in the database, so the
 * service attaches it ({@link #useCalendar}) before using such a clock; a 24/7 clock needs nothing.
 *
 * <p>Methods take {@code now} from the caller, which reads the injected {@code Clock}, so tests can
 * drive time exactly. A call in the wrong state is a programming error (the SLA reacts to ticket
 * transitions the workflow already validated) and throws {@link IllegalStateException}.
 */
@Entity
@Table(name = "sla_clocks")
class SlaClock extends BaseEntity {

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SlaMetric metric;

    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SlaClockState state;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "running_since")
    private Instant runningSince;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "breached_at")
    private Instant breachedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "business_hours", nullable = false, updatable = false)
    private boolean businessHours;

    /** When a running clock becomes at risk; kept current by {@link #refreshAtRisk}. */
    @Column(name = "at_risk_at")
    private Instant atRiskAt;

    @Transient
    private SlaSchedule calendar;

    protected SlaClock() {}

    private SlaClock(UUID workItemId, SlaMetric metric, Duration target, SlaSchedule schedule) {
        this.workItemId = workItemId;
        this.metric = metric;
        this.targetMinutes = toMinutes(target);
        this.businessHours = schedule.isBusinessHours();
        this.calendar = schedule;
    }

    /** A 24/7 clock that starts counting at {@code now}. */
    static SlaClock start(UUID workItemId, SlaMetric metric, Duration target, Instant now) {
        return start(workItemId, metric, target, SlaSchedule.ALWAYS, now);
    }

    /** A clock counting time on {@code schedule}, starting at {@code now}. */
    static SlaClock start(UUID workItemId, SlaMetric metric, Duration target, SlaSchedule schedule, Instant now) {
        SlaClock clock = new SlaClock(workItemId, metric, target, schedule);
        clock.startStretch(now);
        return clock;
    }

    boolean isBusinessHours() {
        return businessHours;
    }

    /** Gives a business-hours clock the calendar to count with. Ignored by 24/7 clocks. */
    void useCalendar(SlaSchedule businessCalendar) {
        if (businessHours) {
            this.calendar = businessCalendar;
        }
    }

    Instant getAtRiskAt() {
        return atRiskAt;
    }

    /**
     * Sets when a running clock crosses {@code atRiskFraction} of its target, so the queue can
     * compare instants instead of counting working time. Stopped clocks have none.
     */
    void refreshAtRisk(double atRiskFraction) {
        if (state != SlaClockState.RUNNING) {
            atRiskAt = null;
            return;
        }
        long untilAtRisk = Math.round(target().toMillis() * atRiskFraction) - elapsedMs;
        atRiskAt = schedule().addWorkingTime(runningSince, Duration.ofMillis(untilAtRisk));
    }

    /**
     * Recomputes a running clock's deadlines after the calendar changed. Time already folded into
     * {@code elapsedMs} stays as counted; the current stretch is counted with the new hours.
     */
    void recalculate(double atRiskFraction) {
        if (state == SlaClockState.RUNNING) {
            dueAt = deadlineFrom(runningSince);
            refreshAtRisk(atRiskFraction);
        }
    }

    UUID getWorkItemId() {
        return workItemId;
    }

    SlaMetric getMetric() {
        return metric;
    }

    SlaClockState getState() {
        return state;
    }

    Duration target() {
        return Duration.ofMinutes(targetMinutes);
    }

    /** The deadline while running; {@code null} otherwise, because a stopped clock has none. */
    Instant getDueAt() {
        return dueAt;
    }

    Instant getBreachedAt() {
        return breachedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    /** Time counted so far. */
    Duration elapsed(Instant now) {
        Duration counted = Duration.ofMillis(elapsedMs);
        return state == SlaClockState.RUNNING ? counted.plus(schedule().workingTime(runningSince, now)) : counted;
    }

    /** Time left before the target; negative once overdue. */
    Duration remaining(Instant now) {
        return target().minus(elapsed(now));
    }

    /**
     * Records a breach if the deadline has passed. Used by the breach job and before the clock stops.
     *
     * @return {@code true} only the first time, so the caller can raise the breach event once
     */
    boolean recordBreachIfOverdue(Instant now) {
        if (breachedAt != null || state != SlaClockState.RUNNING || now.isBefore(dueAt)) {
            return false;
        }
        breachedAt = dueAt;
        return true;
    }

    /** Stops counting without finishing, e.g. while waiting for the requester. */
    void pause(Instant now) {
        requireState(SlaClockState.RUNNING);
        recordBreachIfOverdue(now);
        endStretch(now);
        state = SlaClockState.PAUSED;
    }

    void resume(Instant now) {
        requireState(SlaClockState.PAUSED);
        startStretch(now);
    }

    /** The measured work is done. */
    void complete(Instant now) {
        requireState(SlaClockState.RUNNING, SlaClockState.PAUSED);
        if (state == SlaClockState.RUNNING) {
            recordBreachIfOverdue(now);
            endStretch(now);
        }
        state = SlaClockState.COMPLETED;
        completedAt = now;
    }

    /** The work turned out not to be done: count on from the time already used. */
    void reopen(Instant now) {
        requireState(SlaClockState.COMPLETED);
        completedAt = null;
        startStretch(now);
    }

    void cancel(Instant now) {
        requireState(SlaClockState.RUNNING, SlaClockState.PAUSED);
        if (state == SlaClockState.RUNNING) {
            endStretch(now);
        }
        state = SlaClockState.CANCELLED;
    }

    /**
     * Applies a new target, e.g. after a priority change, keeping the time already used. If that
     * makes the clock overdue, the breach is recorded now: it wasn't a breach under the old target.
     */
    void retarget(Duration target, Instant now) {
        requireState(SlaClockState.RUNNING, SlaClockState.PAUSED);
        recordBreachIfOverdue(now); // a breach under the old target keeps its original time
        targetMinutes = toMinutes(target);
        if (state == SlaClockState.RUNNING) {
            dueAt = deadlineFrom(runningSince);
        }
        if (breachedAt == null && !remaining(now).isPositive()) {
            breachedAt = now;
        }
    }

    /**
     * @param atRiskFraction share of the target after which a running clock is at risk, e.g. 0.8
     */
    SlaStatus status(Instant now, double atRiskFraction) {
        if (atRiskFraction <= 0 || atRiskFraction > 1) {
            throw new IllegalArgumentException("atRiskFraction must be in (0, 1], was " + atRiskFraction);
        }
        return switch (state) {
            case CANCELLED -> SlaStatus.CANCELLED;
            case COMPLETED -> breachedAt == null ? SlaStatus.MET : SlaStatus.MISSED;
            case RUNNING, PAUSED -> {
                if (breachedAt != null || !remaining(now).isPositive()) {
                    yield SlaStatus.BREACHED;
                }
                if (state == SlaClockState.PAUSED) {
                    yield SlaStatus.PAUSED;
                }
                yield elapsed(now).toMillis() >= target().toMillis() * atRiskFraction
                        ? SlaStatus.AT_RISK
                        : SlaStatus.ON_TRACK;
            }
        };
    }

    private void startStretch(Instant now) {
        state = SlaClockState.RUNNING;
        runningSince = now;
        dueAt = deadlineFrom(now);
    }

    private void endStretch(Instant now) {
        elapsedMs += schedule().workingTime(runningSince, now).toMillis();
        runningSince = null;
        dueAt = null;
        atRiskAt = null;
    }

    private Instant deadlineFrom(Instant stretchStart) {
        return schedule().addWorkingTime(stretchStart, target().minusMillis(elapsedMs));
    }

    private SlaSchedule schedule() {
        if (!businessHours) {
            return SlaSchedule.ALWAYS;
        }
        if (calendar == null) {
            throw new IllegalStateException("Business-hours SLA clock " + metric + " used without its calendar");
        }
        return calendar;
    }

    private void requireState(SlaClockState... allowed) {
        for (SlaClockState candidate : allowed) {
            if (state == candidate) {
                return;
            }
        }
        throw new IllegalStateException("SLA clock " + metric + " is " + state);
    }

    private static int toMinutes(Duration target) {
        if (!target.isPositive() || !target.equals(Duration.ofMinutes(target.toMinutes()))) {
            throw new IllegalArgumentException("SLA targets are whole, positive minutes, was " + target);
        }
        return Math.toIntExact(target.toMinutes());
    }
}
