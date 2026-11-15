package com.ademola.esm.sla;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a ticket's SLA clocks, with the same visibility rules as its history. */
@Service
@Transactional(readOnly = true)
class SlaQueryService {

    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final SlaClockRepository clocks;
    private final SlaProperties properties;
    private final BusinessCalendarService calendars;
    private final Clock clock;

    SlaQueryService(
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            SlaClockRepository clocks,
            SlaProperties properties,
            BusinessCalendarService calendars,
            Clock clock) {
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.clocks = clocks;
        this.properties = properties;
        this.calendars = calendars;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    TicketSlaResponse forTicket(CurrentUser user, UUID ticketId) {
        Set<UUID> teamScope = accessPolicy.teamScopeOf(user);
        WorkItem item = workItems
                .findById(ticketId)
                .filter(found -> TicketAccessPolicy.canView(user, found, teamScope))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        if (!TicketAccessPolicy.canSeeStaffDetails(user, item, teamScope)) {
            throw new BusinessRuleException(
                    ErrorCode.ACCESS_DENIED, "Service levels are available to the support staff handling the ticket");
        }
        Instant now = clock.instant();
        List<SlaClock> ticketClocks = clocks.findByWorkItemId(ticketId);
        if (ticketClocks.stream().anyMatch(SlaClock::isBusinessHours)) {
            BusinessCalendar calendar = calendars.calendar();
            ticketClocks.forEach(slaClock -> slaClock.useCalendar(calendar));
        }
        return new TicketSlaResponse(ticketClocks.stream()
                .sorted(Comparator.comparing(SlaClock::getMetric))
                .map(slaClock -> view(slaClock, now))
                .toList());
    }

    private TicketSlaResponse.SlaClockView view(SlaClock slaClock, Instant now) {
        Duration elapsed = slaClock.elapsed(now);
        return new TicketSlaResponse.SlaClockView(
                slaClock.getMetric(),
                slaClock.status(now, properties.atRiskThreshold()),
                Math.toIntExact(slaClock.target().toMinutes()),
                elapsed.toSeconds(),
                slaClock.remaining(now).toSeconds(),
                slaClock.getDueAt(),
                slaClock.getBreachedAt(),
                slaClock.getCompletedAt(),
                slaClock.isBusinessHours());
    }
}
