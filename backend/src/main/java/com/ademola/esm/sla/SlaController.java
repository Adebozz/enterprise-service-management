package com.ademola.esm.sla;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Service levels")
class SlaController {

    private final SlaQueryService queries;
    private final SlaPolicyService policies;
    private final BusinessCalendarService calendar;

    SlaController(SlaQueryService queries, SlaPolicyService policies, BusinessCalendarService calendar) {
        this.queries = queries;
        this.policies = policies;
        this.calendar = calendar;
    }

    @GetMapping("/api/tickets/{id}/sla")
    @Operation(
            operationId = "getTicketSla",
            summary = "Response and resolution clocks of the ticket (support staff handling it, and admins)")
    TicketSlaResponse ticketSla(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return queries.forTicket(user, id);
    }

    @GetMapping("/api/admin/sla-policies")
    @Operation(operationId = "listSlaPolicies", summary = "SLA targets for every ticket type and priority")
    List<SlaPolicyResponse> listPolicies() {
        return policies.list();
    }

    @PutMapping("/api/admin/sla-policies/{id}")
    @Operation(
            operationId = "updateSlaPolicy",
            summary = "Change the SLA targets of one type and priority (applies to tickets raised afterwards)")
    SlaPolicyResponse updatePolicy(@PathVariable UUID id, @Valid @RequestBody UpdateSlaPolicyRequest request) {
        return policies.update(id, request);
    }

    @GetMapping("/api/admin/business-hours")
    @Operation(
            operationId = "getBusinessHours",
            summary = "The business calendar: time zone, opening hours and holidays")
    BusinessHoursResponse businessHours() {
        return calendar.view();
    }

    @PutMapping("/api/admin/business-hours/week")
    @Operation(
            operationId = "updateBusinessWeek",
            summary =
                    "Replace the opening hours (one entry per open day); running business-hours clocks follow at once")
    BusinessHoursResponse updateWeek(@Valid @RequestBody UpdateBusinessWeekRequest request) {
        return calendar.updateWeek(request);
    }

    @PostMapping("/api/admin/business-hours/holidays")
    @Operation(operationId = "addHoliday", summary = "Close the desk for a whole day")
    BusinessHoursResponse addHoliday(@Valid @RequestBody BusinessHoursResponse.Holiday holiday) {
        return calendar.addHoliday(holiday);
    }

    @DeleteMapping("/api/admin/business-hours/holidays/{day}")
    @Operation(operationId = "removeHoliday", summary = "Open the desk again on a former holiday")
    BusinessHoursResponse removeHoliday(@PathVariable LocalDate day) {
        return calendar.removeHoliday(day);
    }
}
