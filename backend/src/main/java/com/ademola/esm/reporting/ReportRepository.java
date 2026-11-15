package com.ademola.esm.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregate SQL over tickets, SLA clocks and changes. Every query is scoped to the same
 * teams and takes the period as instants ({@code [:start, :end)}), so the database can use the
 * timestamp indexes; grouping by day happens in the report's time zone.
 */
@Repository
class ReportRepository {

    /** Incidents and requests: the work measured by service levels. */
    private static final String DESK_WORK = "w.type IN ('INCIDENT', 'SERVICE_REQUEST')";

    private static final String OPEN = "w.status NOT IN ('RESOLVED', 'FULFILLED', 'CLOSED', 'CANCELLED', 'REJECTED')";

    private final NamedParameterJdbcTemplate jdbc;

    ReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Scope(Instant start, Instant end, String zone, Collection<UUID> teams) {

        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("start", Timestamp.from(start))
                    .addValue("end", Timestamp.from(end))
                    .addValue("zone", zone)
                    .addValue("teams", teams);
        }
    }

    long count(Scope scope, String column) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM work_items w WHERE " + DESK_WORK + " AND w.assigned_team_id IN (:teams)"
                        + " AND w." + column + " >= :start AND w." + column + " < :end",
                scope.params(),
                Long.class);
    }

    long openNow(Scope scope) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM work_items w WHERE " + DESK_WORK + " AND w.assigned_team_id IN (:teams) AND "
                        + OPEN,
                scope.params(),
                Long.class);
    }

    long breachedOpen(Scope scope) {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT w.id) FROM work_items w JOIN sla_clocks s ON s.work_item_id = w.id"
                        + " WHERE " + DESK_WORK + " AND w.assigned_team_id IN (:teams) AND " + OPEN
                        + " AND s.state IN ('RUNNING', 'PAUSED') AND s.breached_at IS NOT NULL",
                scope.params(),
                Long.class);
    }

    /** Created and resolved per local day; days with nothing are filled in by the caller. */
    Map<LocalDate, long[]> daily(Scope scope) {
        Map<LocalDate, long[]> days = new LinkedHashMap<>();
        for (String[] kind : new String[][] {{"created_at", "0"}, {"resolved_at", "1"}}) {
            int slot = Integer.parseInt(kind[1]);
            jdbc.query(
                    "SELECT (w." + kind[0] + " AT TIME ZONE :zone)::date AS day, count(*) AS n FROM work_items w"
                            + " WHERE " + DESK_WORK + " AND w.assigned_team_id IN (:teams)"
                            + " AND w." + kind[0] + " >= :start AND w." + kind[0] + " < :end GROUP BY 1",
                    scope.params(),
                    rs -> {
                        days.computeIfAbsent(rs.getDate("day").toLocalDate(), day -> new long[2])[slot] =
                                rs.getLong("n");
                    });
        }
        return days;
    }

    List<ServiceDeskReport.PriorityCount> backlogByPriority(Scope scope) {
        return jdbc.query(
                "SELECT w.priority, count(*) AS n FROM work_items w WHERE " + DESK_WORK
                        + " AND w.assigned_team_id IN (:teams) AND " + OPEN
                        + " GROUP BY w.priority ORDER BY w.priority",
                scope.params(),
                (rs, row) -> new ServiceDeskReport.PriorityCount(rs.getString(1), rs.getLong(2)));
    }

    List<ServiceDeskReport.SlaCompliance> slaCompliance(Scope scope) {
        List<ServiceDeskReport.SlaCompliance> result = new ArrayList<>();
        jdbc.query(
                "SELECT s.metric, count(*) FILTER (WHERE s.breached_at IS NULL) AS met,"
                        + " count(*) FILTER (WHERE s.breached_at IS NOT NULL) AS missed"
                        + " FROM sla_clocks s JOIN work_items w ON w.id = s.work_item_id"
                        + " WHERE w.assigned_team_id IN (:teams) AND s.state = 'COMPLETED'"
                        + " AND s.completed_at >= :start AND s.completed_at < :end GROUP BY s.metric",
                scope.params(),
                rs -> {
                    long met = rs.getLong("met");
                    long missed = rs.getLong("missed");
                    result.add(new ServiceDeskReport.SlaCompliance(
                            rs.getString("metric"), met, missed, percent(met, met + missed)));
                });
        return result;
    }

    /** Medians, which a few very old tickets can't skew the way they would an average. */
    List<ServiceDeskReport.PriorityTimes> times(Scope scope) {
        return jdbc.query(
                """
                SELECT p.priority,
                       (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM w.first_responded_at - w.created_at) / 60)
                        FROM work_items w WHERE %1$s AND w.assigned_team_id IN (:teams) AND w.priority = p.priority
                          AND w.created_at >= :start AND w.created_at < :end AND w.first_responded_at IS NOT NULL) AS response,
                       (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM w.resolved_at - w.created_at) / 60)
                        FROM work_items w WHERE %1$s AND w.assigned_team_id IN (:teams) AND w.priority = p.priority
                          AND w.resolved_at >= :start AND w.resolved_at < :end) AS resolution,
                       (SELECT count(*)
                        FROM work_items w WHERE %1$s AND w.assigned_team_id IN (:teams) AND w.priority = p.priority
                          AND w.resolved_at >= :start AND w.resolved_at < :end) AS resolved
                FROM (VALUES ('P1'), ('P2'), ('P3'), ('P4')) AS p (priority)
                ORDER BY p.priority
                """.formatted(DESK_WORK),
                scope.params(),
                (rs, row) -> new ServiceDeskReport.PriorityTimes(
                        rs.getString("priority"),
                        tenths(rs.getObject("response", Double.class)),
                        tenths(rs.getObject("resolution", Double.class)),
                        rs.getLong("resolved")));
    }

    List<ServiceDeskReport.CategoryCount> topCategories(Scope scope, int limit) {
        return jdbc.query(
                "SELECT c.id, c.name, count(*) AS n FROM work_items w JOIN categories c ON c.id = w.category_id"
                        + " WHERE " + DESK_WORK + " AND w.assigned_team_id IN (:teams)"
                        + " AND w.created_at >= :start AND w.created_at < :end"
                        + " GROUP BY c.id, c.name ORDER BY n DESC, c.name LIMIT " + limit,
                scope.params(),
                (rs, row) -> new ServiceDeskReport.CategoryCount(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)));
    }

    List<ServiceDeskReport.OutcomeCount> changeOutcomes(Scope scope) {
        return jdbc.query(
                "SELECT ch.outcome, count(*) FROM changes ch JOIN work_items w ON w.id = ch.work_item_id"
                        + " WHERE w.assigned_team_id IN (:teams) AND w.status = 'CLOSED' AND ch.outcome IS NOT NULL"
                        + " AND w.closed_at >= :start AND w.closed_at < :end GROUP BY ch.outcome ORDER BY 2 DESC, 1",
                scope.params(),
                (rs, row) -> new ServiceDeskReport.OutcomeCount(rs.getString(1), rs.getLong(2)));
    }

    /** Minutes to one decimal: finer than anyone reads, and stable for clients. */
    private static Double tenths(Double minutes) {
        return minutes == null ? null : Math.round(minutes * 10) / 10.0;
    }

    private static Double percent(long part, long whole) {
        return whole == 0 ? null : Math.round(1000.0 * part / whole) / 10.0;
    }
}
