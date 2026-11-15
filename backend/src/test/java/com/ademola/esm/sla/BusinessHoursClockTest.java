package com.ademola.esm.sla;

import static com.ademola.esm.sla.BusinessCalendarTest.FRIDAY;
import static com.ademola.esm.sla.BusinessCalendarTest.MONDAY;
import static com.ademola.esm.sla.BusinessCalendarTest.WEEKDAYS;
import static com.ademola.esm.sla.BusinessCalendarTest.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A clock counting business hours: deadlines, pauses, at-risk times and calendar changes. */
class BusinessHoursClockTest {

    static final Duration FOUR_HOURS = Duration.ofHours(4);

    @Test
    void aTicketRaisedLateOnFridayIsDueOnMonday() {
        SlaClock clock =
                SlaClock.start(UUID.randomUUID(), SlaMetric.RESOLUTION, FOUR_HOURS, WEEKDAYS, at(FRIDAY, "16:30"));

        assertThat(clock.isBusinessHours()).isTrue();
        assertThat(clock.getDueAt()).isEqualTo(at(MONDAY, "12:00"));
        assertThat(clock.elapsed(at(MONDAY, "09:00"))).isEqualTo(Duration.ofHours(1));
        assertThat(clock.status(at(MONDAY, "09:00"), 0.8)).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(clock.status(at(MONDAY, "12:00"), 0.8)).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void theAtRiskMomentIsCountedInWorkingTimeToo() {
        SlaClock clock =
                SlaClock.start(UUID.randomUUID(), SlaMetric.RESOLUTION, FOUR_HOURS, WEEKDAYS, at(FRIDAY, "16:30"));
        clock.refreshAtRisk(0.8);

        // 80% of 4 h is 3 h 12 min: 1 h on Friday, then 2 h 12 min on Monday morning.
        assertThat(clock.getAtRiskAt()).isEqualTo(at(MONDAY, "11:12"));
        assertThat(clock.status(at(MONDAY, "11:12"), 0.8)).isEqualTo(SlaStatus.AT_RISK);
    }

    @Test
    void waitingOverTheWeekendNeitherCountsNorMovesTheRemainingTime() {
        SlaClock clock =
                SlaClock.start(UUID.randomUUID(), SlaMetric.RESOLUTION, FOUR_HOURS, WEEKDAYS, at(FRIDAY, "15:00"));
        clock.pause(at(FRIDAY, "16:00"));
        clock.refreshAtRisk(0.8);
        assertThat(clock.getAtRiskAt()).as("stopped clocks have none").isNull();

        clock.resume(at(MONDAY, "08:00"));

        assertThat(clock.elapsed(at(MONDAY, "08:00"))).isEqualTo(Duration.ofHours(1));
        assertThat(clock.getDueAt()).isEqualTo(at(MONDAY, "12:00"));
    }

    @Test
    void aNewHolidayMovesTheDeadlineOfARunningClock() {
        SlaClock clock =
                SlaClock.start(UUID.randomUUID(), SlaMetric.RESOLUTION, FOUR_HOURS, WEEKDAYS, at(FRIDAY, "16:30"));

        clock.useCalendar(BusinessCalendarTest.calendar(Set.of(MONDAY)));
        clock.recalculate(0.8);

        assertThat(clock.getDueAt()).isEqualTo(at(MONDAY.plusDays(1), "12:00"));
        assertThat(clock.getAtRiskAt()).isEqualTo(at(MONDAY.plusDays(1), "11:12"));
    }

    @Test
    void aBusinessHoursClockCannotBeUsedWithoutItsCalendar() throws Exception {
        SlaClock clock =
                SlaClock.start(UUID.randomUUID(), SlaMetric.RESPONSE, FOUR_HOURS, WEEKDAYS, at(FRIDAY, "10:00"));
        // As loaded from the database: the calendar isn't persisted with the clock.
        var field = SlaClock.class.getDeclaredField("calendar");
        field.setAccessible(true);
        field.set(clock, null);

        assertThatThrownBy(() -> clock.elapsed(at(FRIDAY, "11:00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without its calendar");
    }

    @Test
    void aroundTheClockClocksIgnoreCalendarsAndKeepTheirArithmetic() {
        Instant start = at(FRIDAY, "16:30");
        SlaClock clock = SlaClock.start(UUID.randomUUID(), SlaMetric.RESOLUTION, FOUR_HOURS, start);
        clock.useCalendar(WEEKDAYS);
        clock.refreshAtRisk(0.8);

        assertThat(clock.isBusinessHours()).isFalse();
        assertThat(clock.getDueAt()).isEqualTo(start.plus(FOUR_HOURS));
        assertThat(clock.getAtRiskAt()).isEqualTo(start.plus(Duration.ofMinutes(192)));
        clock.recalculate(0.8);
        assertThat(clock.getDueAt()).isEqualTo(start.plus(FOUR_HOURS));
    }
}
