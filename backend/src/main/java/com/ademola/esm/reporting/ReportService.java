package com.ademola.esm.reporting;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.user.Role;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service desk report. Team leads see their own teams, admins every team; the scope is part
 * of every query, so a report never counts a ticket its reader couldn't be responsible for.
 */
@Service
public class ReportService {

    /** A year is plenty for trends, and keeps the day-by-day series small. */
    static final int MAX_DAYS = 366;

    static final int TOP_CATEGORIES = 5;

    private final ReportRepository reports;
    private final TeamService teams;
    private final Clock clock;
    private final ZoneId zone;

    /**
     * @param zone the organisation's time zone (the business calendar's), for day boundaries; read
     *     by key because {@code reporting} doesn't depend on {@code sla}
     */
    ReportService(
            ReportRepository reports,
            TeamService teams,
            Clock clock,
            @Value("${esm.sla.business-hours.zone:Europe/London}") String zone) {
        this.reports = reports;
        this.teams = teams;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /**
     * @param from first day included; defaults to 29 days before {@code to}
     * @param to last day included; defaults to today
     * @param teamId one team, or {@code null} for all the caller's teams (all teams for an admin)
     */
    @PreAuthorize("hasRole('TEAM_LEAD')")
    @Transactional(readOnly = true)
    public ServiceDeskReport serviceDesk(CurrentUser user, LocalDate from, LocalDate to, UUID teamId) {
        LocalDate last = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
        LocalDate first = from != null ? from : last.minusDays(29);
        long days = ChronoUnit.DAYS.between(first, last) + 1;
        if (days < 1 || days > MAX_DAYS) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_REPORT_PERIOD,
                    "The period must start on or before its last day and span at most %d days".formatted(MAX_DAYS));
        }
        Collection<UUID> scope = teamScope(user, teamId);
        if (scope.isEmpty()) {
            return empty(first, last, teamId);
        }
        ReportRepository.Scope query = new ReportRepository.Scope(
                first.atStartOfDay(zone).toInstant(),
                last.plusDays(1).atStartOfDay(zone).toInstant(),
                zone.getId(),
                scope);

        Map<LocalDate, long[]> counts = reports.daily(query);
        List<ServiceDeskReport.DailyCount> daily = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            long[] values = counts.getOrDefault(day, new long[2]);
            daily.add(new ServiceDeskReport.DailyCount(day, values[0], values[1]));
        }
        return new ServiceDeskReport(
                first,
                last,
                zone.getId(),
                teamId,
                reports.count(query, "created_at"),
                reports.count(query, "resolved_at"),
                reports.openNow(query),
                reports.breachedOpen(query),
                daily,
                reports.backlogByPriority(query),
                reports.slaCompliance(query),
                reports.times(query),
                reports.topCategories(query, TOP_CATEGORIES),
                reports.changeOutcomes(query));
    }

    /** Admins: one team or all. Team leads: one of their teams, or all of them. */
    private Collection<UUID> teamScope(CurrentUser user, UUID teamId) {
        if (user.role() == Role.ADMIN) {
            return teamId != null ? Set.of(teamId) : teams.allTeamIds();
        }
        Set<UUID> own = teams.teamIdsOf(user.id());
        if (teamId == null) {
            return own;
        }
        if (!own.contains(teamId)) {
            throw new BusinessRuleException(ErrorCode.ACCESS_DENIED, "Team leads see reports for their own teams");
        }
        return Set.of(teamId);
    }

    private ServiceDeskReport empty(LocalDate first, LocalDate last, UUID teamId) {
        List<ServiceDeskReport.DailyCount> daily = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            daily.add(new ServiceDeskReport.DailyCount(day, 0, 0));
        }
        return new ServiceDeskReport(
                first,
                last,
                zone.getId(),
                teamId,
                0,
                0,
                0,
                0,
                daily,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
