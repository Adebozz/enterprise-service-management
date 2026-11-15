package com.ademola.esm.sla;

import java.sql.Date;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The calendar's two small tables. Plain JDBC: rows are values, not entities with a lifecycle. */
@Repository
class BusinessCalendarRepository {

    private final JdbcTemplate jdbc;

    BusinessCalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<BusinessHoursResponse.OpeningDay> openingDays() {
        return jdbc.query(
                "select day_of_week, opens_at, closes_at from business_hours order by day_of_week",
                (rs, row) -> new BusinessHoursResponse.OpeningDay(
                        DayOfWeek.of(rs.getInt(1)),
                        rs.getTime(2).toLocalTime(),
                        rs.getTime(3).toLocalTime()));
    }

    void replaceOpeningDays(List<BusinessHoursResponse.OpeningDay> days) {
        jdbc.update("delete from business_hours");
        for (BusinessHoursResponse.OpeningDay day : days) {
            jdbc.update(
                    "insert into business_hours (day_of_week, opens_at, closes_at) values (?, ?, ?)",
                    day.day().getValue(),
                    Time.valueOf(day.opensAt()),
                    Time.valueOf(day.closesAt()));
        }
    }

    List<BusinessHoursResponse.Holiday> holidays() {
        return jdbc.query(
                "select day, name from business_holidays order by day",
                (rs, row) -> new BusinessHoursResponse.Holiday(rs.getDate(1).toLocalDate(), rs.getString(2)));
    }

    /** @return false if that day was already a holiday */
    boolean addHoliday(LocalDate day, String name) {
        return jdbc.update(
                        "insert into business_holidays (day, name) values (?, ?) on conflict (day) do nothing",
                        Date.valueOf(day),
                        name)
                == 1;
    }

    boolean removeHoliday(LocalDate day) {
        return jdbc.update("delete from business_holidays where day = ?", Date.valueOf(day)) == 1;
    }
}
