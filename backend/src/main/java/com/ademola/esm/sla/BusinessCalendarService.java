package com.ademola.esm.sla;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisation's business calendar: read whenever a business-hours clock needs counting, and
 * maintained by admins. A change applies at once to running business-hours clocks, whose deadlines
 * are recomputed in the same transaction, so the queue never shows a deadline from old hours.
 */
@Service
class BusinessCalendarService {

    /** The calendar is a single, fixed thing; its audit entries use this id. */
    static final UUID CALENDAR_ID = new UUID(0L, 1L);

    private final BusinessCalendarRepository repository;
    private final SlaClockRepository clocks;
    private final SlaProperties properties;
    private final AuditService audit;

    BusinessCalendarService(
            BusinessCalendarRepository repository,
            SlaClockRepository clocks,
            SlaProperties properties,
            AuditService audit) {
        this.repository = repository;
        this.clocks = clocks;
        this.properties = properties;
        this.audit = audit;
    }

    /** The calendar as it is now. Two small reads; called only when a business-hours clock is involved. */
    @Transactional(readOnly = true)
    BusinessCalendar calendar() {
        Map<DayOfWeek, BusinessCalendar.OpeningHours> week = new EnumMap<>(DayOfWeek.class);
        repository
                .openingDays()
                .forEach(day -> week.put(day.day(), new BusinessCalendar.OpeningHours(day.opensAt(), day.closesAt())));
        if (week.isEmpty()) {
            throw new IllegalStateException(
                    "The business calendar has no open days; V15 seeds them and admins can't remove them all");
        }
        Set<LocalDate> holidays = repository.holidays().stream()
                .map(BusinessHoursResponse.Holiday::day)
                .collect(Collectors.toSet());
        return new BusinessCalendar(properties.businessHours().zoneId(), week, holidays);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    BusinessHoursResponse view() {
        return new BusinessHoursResponse(
                properties.businessHours().zone(), repository.openingDays(), repository.holidays());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    BusinessHoursResponse updateWeek(UpdateBusinessWeekRequest request) {
        List<BusinessHoursResponse.OpeningDay> days = request.days();
        Set<DayOfWeek> seen = new HashSet<>();
        for (BusinessHoursResponse.OpeningDay day : days) {
            if (!seen.add(day.day())) {
                throw invalid("%s is listed twice".formatted(day.day()));
            }
            if (!day.closesAt().isAfter(day.opensAt())) {
                throw invalid("On %s the desk must close after it opens".formatted(day.day()));
            }
        }
        List<BusinessHoursResponse.OpeningDay> before = repository.openingDays();
        List<BusinessHoursResponse.OpeningDay> sorted =
                days.stream().sorted((a, b) -> a.day().compareTo(b.day())).toList();
        repository.replaceOpeningDays(sorted);
        audit.record(AuditRecord.changed(
                AuditAction.BUSINESS_HOURS_UPDATED,
                AuditEntityType.BUSINESS_CALENDAR,
                CALENDAR_ID,
                Map.of("week", describe(before)),
                Map.of("week", describe(sorted))));
        recalculateRunningClocks();
        return view();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    BusinessHoursResponse addHoliday(BusinessHoursResponse.Holiday holiday) {
        if (!repository.addHoliday(holiday.day(), holiday.name().trim())) {
            throw invalid("%s is already a holiday".formatted(holiday.day()));
        }
        audit.record(AuditRecord.event(
                AuditAction.BUSINESS_HOURS_UPDATED,
                AuditEntityType.BUSINESS_CALENDAR,
                CALENDAR_ID,
                Map.of(
                        "holidayAdded",
                        holiday.day().toString(),
                        "name",
                        holiday.name().trim())));
        recalculateRunningClocks();
        return view();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    BusinessHoursResponse removeHoliday(LocalDate day) {
        if (!repository.removeHoliday(day)) {
            throw new ResourceNotFoundException("Holiday", day);
        }
        audit.record(AuditRecord.event(
                AuditAction.BUSINESS_HOURS_UPDATED,
                AuditEntityType.BUSINESS_CALENDAR,
                CALENDAR_ID,
                Map.of("holidayRemoved", day.toString())));
        recalculateRunningClocks();
        return view();
    }

    private void recalculateRunningClocks() {
        BusinessCalendar calendar = calendar();
        for (SlaClock clock : clocks.findByStateAndBusinessHoursTrue(SlaClockState.RUNNING)) {
            clock.useCalendar(calendar);
            clock.recalculate(properties.atRiskThreshold());
        }
    }

    private static String describe(List<BusinessHoursResponse.OpeningDay> days) {
        return days.stream()
                .map(day -> "%s %s-%s".formatted(day.day(), day.opensAt(), day.closesAt()))
                .collect(Collectors.joining(", "));
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException(ErrorCode.INVALID_BUSINESS_HOURS, message);
    }
}
