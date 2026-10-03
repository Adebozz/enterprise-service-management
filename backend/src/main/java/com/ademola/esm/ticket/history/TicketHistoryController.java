package com.ademola.esm.ticket.history;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Ticket history")
class TicketHistoryController {

    private final TicketHistoryService historyService;

    TicketHistoryController(TicketHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/api/tickets/{id}/history")
    @Operation(summary = "Audit timeline of the ticket (support staff handling it, and admins)")
    List<HistoryEntryResponse> history(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return historyService.history(user, id);
    }
}
