package com.ademola.esm.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The clock arithmetic, driven by exact instants instead of waiting. Target: 4 hours. */
class SlaClockTest {

    static final Instant T0 = Instant.parse("2026-11-11T09:00:00Z");
    static final Duration FOUR_HOURS = Duration.ofHours(4);
    static final double AT_RISK = 0.8;

    SlaClock clock = SlaClock.start(UUID.randomUUID(), SlaMetric.RESOLUTION, FOUR_HOURS, T0);

    static Instant at(Duration sinceStart) {
        return T0.plus(sinceStart);
    }

    @Test
    void startsRunningWithTheDeadlineOneTargetAway() {
        assertThat(clock.getState()).isEqualTo(SlaClockState.RUNNING);
        assertThat(clock.getDueAt()).isEqualTo(at(FOUR_HOURS));
        assertThat(clock.elapsed(at(Duration.ofHours(1)))).isEqualTo(Duration.ofHours(1));
        assertThat(clock.remaining(at(Duration.ofHours(1)))).isEqualTo(Duration.ofHours(3));
        assertThat(clock.status(at(Duration.ofHours(1)), AT_RISK)).isEqualTo(SlaStatus.ON_TRACK);
    }

    @Test
    void isAtRiskOnceTheThresholdOfTheTargetIsUsed() {
        Duration eightyPercent = Duration.ofMinutes(192); // 80% of 240 minutes

        assertThat(clock.status(at(eightyPercent.minusMillis(1)), AT_RISK)).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(clock.status(at(eightyPercent), AT_RISK)).isEqualTo(SlaStatus.AT_RISK);
        assertThat(clock.status(at(FOUR_HOURS), AT_RISK)).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void pausingFreezesTheTimeUsedAndResumingMovesTheDeadline() {
        clock.pause(at(Duration.ofHours(1)));

        Instant muchLater = at(Duration.ofHours(30));
        assertThat(clock.getState()).isEqualTo(SlaClockState.PAUSED);
        assertThat(clock.getDueAt()).isNull();
        assertThat(clock.elapsed(muchLater)).isEqualTo(Duration.ofHours(1));
        assertThat(clock.status(muchLater, AT_RISK)).isEqualTo(SlaStatus.PAUSED);

        clock.resume(muchLater);

        assertThat(clock.getDueAt()).isEqualTo(muchLater.plus(Duration.ofHours(3)));
        assertThat(clock.elapsed(muchLater.plus(Duration.ofHours(2)))).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void recordsABreachOnceAtTheMomentTheDeadlinePassed() {
        assertThat(clock.recordBreachIfOverdue(at(FOUR_HOURS).minusMillis(1))).isFalse();

        assertThat(clock.recordBreachIfOverdue(at(Duration.ofHours(5)))).isTrue();
        assertThat(clock.recordBreachIfOverdue(at(Duration.ofHours(6)))).isFalse();

        assertThat(clock.getBreachedAt()).isEqualTo(at(FOUR_HOURS));
        assertThat(clock.getState()).isEqualTo(SlaClockState.RUNNING);
        assertThat(clock.status(at(Duration.ofHours(6)), AT_RISK)).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void completingWithinTheTargetIsMet() {
        clock.complete(at(Duration.ofHours(3)));

        assertThat(clock.getState()).isEqualTo(SlaClockState.COMPLETED);
        assertThat(clock.getCompletedAt()).isEqualTo(at(Duration.ofHours(3)));
        assertThat(clock.getDueAt()).isNull();
        assertThat(clock.getBreachedAt()).isNull();
        assertThat(clock.status(at(Duration.ofDays(9)), AT_RISK)).isEqualTo(SlaStatus.MET);
    }

    @Test
    void completingLateIsMissedEvenIfTheBreachJobNeverRan() {
        clock.complete(at(Duration.ofHours(5)));

        assertThat(clock.getBreachedAt()).isEqualTo(at(FOUR_HOURS));
        assertThat(clock.status(at(Duration.ofHours(5)), AT_RISK)).isEqualTo(SlaStatus.MISSED);
    }

    @Test
    void pausingAfterTheDeadlineKeepsTheBreach() {
        clock.pause(at(Duration.ofHours(5)));

        assertThat(clock.getBreachedAt()).isEqualTo(at(FOUR_HOURS));
        assertThat(clock.status(at(Duration.ofHours(5)), AT_RISK)).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void completingAPausedClockKeepsItsTime() {
        clock.pause(at(Duration.ofHours(1)));
        clock.complete(at(Duration.ofHours(10)));

        assertThat(clock.elapsed(at(Duration.ofHours(10)))).isEqualTo(Duration.ofHours(1));
        assertThat(clock.status(at(Duration.ofHours(10)), AT_RISK)).isEqualTo(SlaStatus.MET);
    }

    @Test
    void reopeningContinuesFromTheTimeAlreadyUsed() {
        clock.complete(at(Duration.ofHours(1)));

        Instant reopenedAt = at(Duration.ofHours(10));
        clock.reopen(reopenedAt);

        assertThat(clock.getState()).isEqualTo(SlaClockState.RUNNING);
        assertThat(clock.getCompletedAt()).isNull();
        assertThat(clock.elapsed(reopenedAt)).isEqualTo(Duration.ofHours(1));
        assertThat(clock.getDueAt()).isEqualTo(reopenedAt.plus(Duration.ofHours(3)));
    }

    @Test
    void reopeningAMissedClockKeepsItBreached() {
        clock.complete(at(Duration.ofHours(5)));
        clock.reopen(at(Duration.ofHours(6)));

        assertThat(clock.getBreachedAt()).isEqualTo(at(FOUR_HOURS));
        assertThat(clock.status(at(Duration.ofHours(6)), AT_RISK)).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void aLongerTargetMovesTheDeadlineAndKeepsTheTimeUsed() {
        clock.retarget(Duration.ofHours(8), at(Duration.ofHours(2)));

        assertThat(clock.getDueAt()).isEqualTo(at(Duration.ofHours(8)));
        assertThat(clock.getBreachedAt()).isNull();
    }

    @Test
    void aShorterTargetThatIsAlreadyUsedUpBreachesAtTheChange() {
        Instant changedAt = at(Duration.ofHours(2));
        clock.retarget(Duration.ofHours(1), changedAt);

        assertThat(clock.getDueAt()).isEqualTo(at(Duration.ofHours(1)));
        assertThat(clock.getBreachedAt()).isEqualTo(changedAt);
    }

    @Test
    void aBreachUnderTheOldTargetKeepsItsTimeWhenTheTargetGrows() {
        clock.retarget(Duration.ofHours(8), at(Duration.ofHours(5)));

        assertThat(clock.getBreachedAt()).isEqualTo(at(FOUR_HOURS));
        assertThat(clock.status(at(Duration.ofHours(5)), AT_RISK)).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void retargetingAPausedClockChecksTheTimeAlreadyUsed() {
        clock.pause(at(Duration.ofHours(2)));

        clock.retarget(Duration.ofHours(3), at(Duration.ofHours(3)));
        assertThat(clock.getBreachedAt()).isNull();
        assertThat(clock.getDueAt()).isNull();

        Instant shortenedAt = at(Duration.ofHours(4));
        clock.retarget(Duration.ofHours(1), shortenedAt);
        assertThat(clock.getBreachedAt()).isEqualTo(shortenedAt);
    }

    @Test
    void aCancelledClockStopsCountingAndIsReportedAsCancelled() {
        clock.cancel(at(Duration.ofHours(1)));

        assertThat(clock.getState()).isEqualTo(SlaClockState.CANCELLED);
        assertThat(clock.getDueAt()).isNull();
        assertThat(clock.elapsed(at(Duration.ofHours(9)))).isEqualTo(Duration.ofHours(1));
        assertThat(clock.status(at(Duration.ofHours(9)), AT_RISK)).isEqualTo(SlaStatus.CANCELLED);
    }

    @Test
    void aPausedClockCanBeCancelled() {
        clock.pause(at(Duration.ofHours(1)));
        clock.cancel(at(Duration.ofHours(2)));

        assertThat(clock.getState()).isEqualTo(SlaClockState.CANCELLED);
        assertThat(clock.elapsed(at(Duration.ofHours(2)))).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void breachesAreOnlyRecordedForRunningClocks() {
        clock.pause(at(Duration.ofHours(1)));

        assertThat(clock.recordBreachIfOverdue(at(Duration.ofDays(3)))).isFalse();
        assertThat(clock.getBreachedAt()).isNull();
    }

    @Test
    void callsInTheWrongStateAreRejected() {
        assertThatThrownBy(() -> clock.resume(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clock.reopen(T0)).isInstanceOf(IllegalStateException.class);

        clock.complete(at(Duration.ofHours(1)));
        assertThatThrownBy(() -> clock.pause(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clock.complete(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clock.cancel(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> clock.retarget(FOUR_HOURS, T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESOLUTION is COMPLETED");
    }

    @Test
    void targetsAreWholePositiveMinutes() {
        UUID ticket = UUID.randomUUID();

        assertThatThrownBy(() -> SlaClock.start(ticket, SlaMetric.RESPONSE, Duration.ZERO, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlaClock.start(ticket, SlaMetric.RESPONSE, Duration.ofSeconds(90), T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAtRiskThresholdMustBeAFraction() {
        assertThatThrownBy(() -> clock.status(T0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.status(T0, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetsMapToTheirMetric() {
        SlaTargets targets = new SlaTargets(Duration.ofMinutes(15), FOUR_HOURS);

        assertThat(targets.forMetric(SlaMetric.RESPONSE)).isEqualTo(Duration.ofMinutes(15));
        assertThat(targets.forMetric(SlaMetric.RESOLUTION)).isEqualTo(FOUR_HOURS);
    }
}
