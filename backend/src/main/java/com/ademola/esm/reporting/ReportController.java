package com.ademola.esm.reporting;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Reports")
class ReportController {

    private final ReportService reportService;

    ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/api/reports/service-desk")
    @Operation(
            operationId = "getServiceDeskReport",
            summary =
                    "Volumes, backlog, SLA compliance, response and resolution times for a period (team leads: own teams)")
    ServiceDeskReport serviceDesk(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID teamId) {
        return reportService.serviceDesk(user, from, to, teamId);
    }
}
