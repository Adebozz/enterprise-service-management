package com.ademola.esm.ticket.query;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Changes")
class ChangeScheduleController {

    private final ChangeScheduleService schedule;

    ChangeScheduleController(ChangeScheduleService schedule) {
        this.schedule = schedule;
    }

    @GetMapping("/api/changes/schedule")
    @Operation(
            operationId = "getChangeSchedule",
            summary = "Changes awaiting approval, approved or under way whose window hasn't ended, soonest first"
                    + " (support staff)")
    List<ScheduledChange> schedule(@AuthenticationPrincipal CurrentUser user) {
        return schedule.upcoming(user);
    }
}
