package com.ademola.esm.ticket.query;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
@Tag(name = "Tickets")
class TicketController {

    private final TicketQueryService ticketQueryService;

    TicketController(TicketQueryService ticketQueryService) {
        this.ticketQueryService = ticketQueryService;
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getTicket",
            summary = "A ticket of any type. 404 if it doesn't exist or the caller may not see it")
    TicketResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return ticketQueryService.get(user, id);
    }
}
