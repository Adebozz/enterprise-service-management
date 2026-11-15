package com.ademola.esm.sla;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The business calendar: its time zone, the days it opens (a missing day is closed) and holidays.
 */
record BusinessHoursResponse(String zone, List<OpeningDay> week, List<Holiday> holidays) {

    record OpeningDay(
            @NotNull DayOfWeek day,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt) {}

    record Holiday(
            @NotNull LocalDate day,
            @NotBlank @Size(max = 100) String name) {}
}
