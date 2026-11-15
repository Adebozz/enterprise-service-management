package com.ademola.esm.sla;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The service desk's working time: opening hours per weekday in one time zone, minus closed days.
 * Immutable and free of I/O, so the arithmetic is unit-tested on its own.
 *
 * <p>Each day's hours are turned into instants through the zone, so daylight-saving changes are
 * handled by {@code java.time}: an 09:00 to 17:30 day is 8.5 hours on either side of the change.
 */
final class BusinessCalendar implements SlaSchedule {

    /** The working hours of one day, in local time. */
    record OpeningHours(LocalTime opens, LocalTime closes) {

        OpeningHours {
            if (!closes.isAfter(opens)) {
                throw new IllegalArgumentException("A day must close after it opens: " + opens + "-" + closes);
            }
        }
    }

    private record Interval(Instant start, Instant end) {}

    /** No real target is longer than a year; a search past this means the calendar is broken. */
    private static final int MAX_DAYS_SEARCHED = 20 * 366;

    private final ZoneId zone;
    private final Map<DayOfWeek, OpeningHours> week;
    private final Set<LocalDate> holidays;

    BusinessCalendar(ZoneId zone, Map<DayOfWeek, OpeningHours> week, Set<LocalDate> holidays) {
        if (week.isEmpty()) {
            throw new IllegalArgumentException("A business calendar needs at least one open day");
        }
        this.zone = zone;
        this.week = Map.copyOf(week);
        this.holidays = Set.copyOf(holidays);
    }

    @Override
    public Duration workingTime(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }
        Duration total = Duration.ZERO;
        LocalDate last = to.atZone(zone).toLocalDate();
        for (LocalDate day = from.atZone(zone).toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
            Optional<Interval> open = openOn(day);
            if (open.isPresent()) {
                Instant start = later(open.get().start(), from);
                Instant end = earlier(open.get().end(), to);
                if (end.isAfter(start)) {
                    total = total.plus(Duration.between(start, end));
                }
            }
        }
        return total;
    }

    @Override
    public Instant addWorkingTime(Instant start, Duration amount) {
        if (!amount.isPositive()) {
            return start;
        }
        Duration left = amount;
        LocalDate day = start.atZone(zone).toLocalDate();
        for (int searched = 0; searched < MAX_DAYS_SEARCHED; searched++, day = day.plusDays(1)) {
            Optional<Interval> open = openOn(day);
            if (open.isEmpty() || !open.get().end().isAfter(start)) {
                continue;
            }
            Instant from = later(open.get().start(), start);
            Duration available = Duration.between(from, open.get().end());
            if (left.compareTo(available) <= 0) {
                return from.plus(left);
            }
            left = left.minus(available);
        }
        throw new IllegalStateException(
                "The business calendar has no working time within " + MAX_DAYS_SEARCHED + " days");
    }

    @Override
    public boolean isBusinessHours() {
        return true;
    }

    private Optional<Interval> openOn(LocalDate day) {
        OpeningHours hours = week.get(day.getDayOfWeek());
        if (hours == null || holidays.contains(day)) {
            return Optional.empty();
        }
        return Optional.of(new Interval(
                day.atTime(hours.opens()).atZone(zone).toInstant(),
                day.atTime(hours.closes()).atZone(zone).toInstant()));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
