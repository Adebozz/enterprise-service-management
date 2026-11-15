package com.ademola.esm.reporting;

import jakarta.annotation.Nullable;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * How the service desk did over a period, for incidents and service requests (the work with
 * service levels). Days are calendar days in {@code zone}; durations are wall-clock minutes.
 *
 * @param teamId the team reported on, or {@code null} for every team the caller may see
 * @param created tickets raised in the period
 * @param resolved tickets resolved or fulfilled in the period
 * @param openNow tickets still being worked on now (not resolved, fulfilled or finished)
 * @param breachedOpen open tickets with a breached SLA target now
 */
public record ServiceDeskReport(
        LocalDate from,
        LocalDate to,
        String zone,
        @Nullable UUID teamId,
        long created,
        long resolved,
        long openNow,
        long breachedOpen,
        List<DailyCount> daily,
        List<PriorityCount> backlogByPriority,
        List<SlaCompliance> sla,
        List<PriorityTimes> times,
        List<CategoryCount> topCategories,
        List<OutcomeCount> changeOutcomes) {

    /** One day: tickets raised and tickets resolved or fulfilled. */
    public record DailyCount(LocalDate day, long created, long resolved) {}

    public record PriorityCount(String priority, long count) {}

    /**
     * SLA targets finished in the period.
     *
     * @param percentMet {@code null} when none finished
     */
    public record SlaCompliance(
            String metric, long met, long missed, @Nullable Double percentMet) {}

    /**
     * Medians in minutes; {@code null} when no ticket of that priority had one in the period.
     *
     * @param firstResponseMinutes for tickets raised in the period that support responded to
     * @param resolutionMinutes for tickets resolved or fulfilled in the period
     */
    public record PriorityTimes(
            String priority,
            @Nullable Double firstResponseMinutes,
            @Nullable Double resolutionMinutes,
            long resolvedCount) {}

    /** Top-level categories by tickets raised in the period. */
    public record CategoryCount(UUID categoryId, String name, long count) {}

    /** Changes closed in the period, by how they went. */
    public record OutcomeCount(String outcome, long count) {}
}
