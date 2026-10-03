package com.ademola.esm.ticket.transition;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.query.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Status changes are their own resource rather than a writable {@code status} field on PATCH: the
 * client asks for a <em>transition</em>, and the server decides whether it's allowed. A client can
 * never simply set {@code "status": "CLOSED"}.
 */
@RestController
@RequestMapping("/api/tickets/{id}/transitions")
@Tag(name = "Ticket workflow")
class TicketTransitionController {

    private final TicketTransitionService transitions;

    TicketTransitionController(TicketTransitionService transitions) {
        this.transitions = transitions;
    }

    @GetMapping
    @Operation(
            operationId = "listAvailableTransitions",
            summary = "Status changes the caller can make now, with labels and required inputs")
    List<AvailableTransition> available(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return transitions.available(user, id);
    }

    @PostMapping
    @Operation(
            operationId = "transitionTicket",
            summary = "Move the ticket to another status (requires current version)")
    TicketResponse transition(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request) {
        return transitions.transition(user, id, request);
    }
}
