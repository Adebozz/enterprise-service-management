package com.ademola.esm.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Working-time arithmetic: weekdays 09:00 to 17:30 in London, with holidays and clock changes. */
class BusinessCalendarTest {

    static final ZoneId LONDON = ZoneId.of("Europe/London");
    // 2026-11-13 is a Friday; 2026-11-16 the Monday after.
    static final LocalDate FRIDAY = LocalDate.of(2026, 11, 13);
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    static final BusinessCalendar WEEKDAYS = calendar(Set.of());

    @Test
    void timeOutsideOpeningHoursDoesNotCount() {
        assertThat(WEEKDAYS.workingTime(at(FRIDAY, "08:00"), at(FRIDAY, "10:00")))
                .isEqualTo(Duration.ofHours(1));
        assertThat(WEEKDAYS.workingTime(at(FRIDAY, "17:00"), at(MONDAY, "09:30")))
                .isEqualTo(Duration.ofHours(1));
        assertThat(WEEKDAYS.workingTime(at(FRIDAY, "18:00"), at(MONDAY, "08:59")))
                .isZero();
        assertThat(WEEKDAYS.workingTime(at(MONDAY, "10:00"), at(FRIDAY, "10:00")))
                .as("backwards")
                .isZero();
    }

    @Test
    void aWholeWorkingWeekIsFiveDaysOfEightAndAHalfHours() {
        assertThat(WEEKDAYS.workingTime(at(MONDAY, "00:00"), at(MONDAY.plusDays(7), "00:00")))
                .isEqualTo(Duration.ofMinutes(5 * 510));
    }

    @Test
    void deadlinesSkipNightsWeekendsAndHolidays() {
        assertThat(WEEKDAYS.addWorkingTime(at(FRIDAY, "17:00"), Duration.ofHours(1)))
                .isEqualTo(at(MONDAY, "09:30"));
        assertThat(WEEKDAYS.addWorkingTime(at(FRIDAY, "07:00"), Duration.ofHours(1)))
                .isEqualTo(at(FRIDAY, "10:00"));
        assertThat(WEEKDAYS.addWorkingTime(at(FRIDAY, "17:30"), Duration.ofMinutes(510)))
                .as("exactly one working day")
                .isEqualTo(at(MONDAY, "17:30"));

        BusinessCalendar mondayOff = calendar(Set.of(MONDAY));
        assertThat(mondayOff.addWorkingTime(at(FRIDAY, "17:00"), Duration.ofHours(1)))
                .isEqualTo(at(MONDAY.plusDays(1), "09:30"));
        assertThat(mondayOff.workingTime(at(FRIDAY, "17:00"), at(MONDAY.plusDays(1), "10:00")))
                .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void addingNothingOrLessThanNothingIsTheStart() {
        Instant saturday = at(FRIDAY.plusDays(1), "12:00");
        assertThat(WEEKDAYS.addWorkingTime(saturday, Duration.ZERO)).isEqualTo(saturday);
        assertThat(WEEKDAYS.addWorkingTime(saturday, Duration.ofMinutes(-5))).isEqualTo(saturday);
    }

    @Test
    void openingHoursFollowTheLocalClockAcrossTheChangeToSummerTime() {
        // Clocks go forward on Sunday 29 March 2026: 09:00 is 09:00 UTC on Friday, 08:00 UTC on Monday.
        LocalDate friday = LocalDate.of(2026, 3, 27);
        LocalDate monday = LocalDate.of(2026, 3, 30);
        assertThat(at(monday, "09:00")).isEqualTo(Instant.parse("2026-03-30T08:00:00Z"));
        assertThat(WEEKDAYS.addWorkingTime(at(friday, "17:30"), Duration.ofHours(1)))
                .isEqualTo(at(monday, "10:00"));
        assertThat(WEEKDAYS.workingTime(at(friday, "09:00"), at(monday, "17:30")))
                .isEqualTo(Duration.ofMinutes(2 * 510));
    }

    @Test
    void aCalendarNeedsAnOpenDayAndEachDayMustCloseAfterItOpens() {
        assertThatThrownBy(() -> new BusinessCalendar(LONDON, Map.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar.OpeningHours(LocalTime.of(17, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCalendarThatNeverOpensFailsInsteadOfLoopingForever() {
        // Open on Mondays only, but every Monday for decades is a holiday: impossible in practice.
        Set<LocalDate> everyMonday = new java.util.HashSet<>();
        for (LocalDate day = MONDAY; day.isBefore(MONDAY.plusYears(21)); day = day.plusWeeks(1)) {
            everyMonday.add(day);
        }
        BusinessCalendar never = new BusinessCalendar(
                LONDON,
                Map.of(DayOfWeek.MONDAY, new BusinessCalendar.OpeningHours(LocalTime.of(9, 0), LocalTime.of(17, 0))),
                everyMonday);
        assertThatThrownBy(() -> never.addWorkingTime(at(FRIDAY, "12:00"), Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(never.isBusinessHours()).isTrue();
        assertThat(SlaSchedule.ALWAYS.isBusinessHours()).isFalse();
        assertThat(SlaSchedule.ALWAYS.workingTime(at(MONDAY, "10:00"), at(FRIDAY, "10:00")))
                .isZero();
        assertThat(SlaSchedule.ALWAYS).hasToString("ALWAYS");
    }

    static BusinessCalendar calendar(Set<LocalDate> holidays) {
        Map<DayOfWeek, BusinessCalendar.OpeningHours> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : new DayOfWeek[] {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        }) {
            week.put(day, new BusinessCalendar.OpeningHours(LocalTime.of(9, 0), LocalTime.of(17, 30)));
        }
        return new BusinessCalendar(LONDON, week, holidays);
    }

    static Instant at(LocalDate day, String time) {
        return LocalDateTime.of(day, LocalTime.parse(time)).atZone(LONDON).toInstant();
    }
}
