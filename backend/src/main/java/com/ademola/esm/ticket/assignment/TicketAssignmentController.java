package com.ademola.esm.ticket.assignment;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Ticket assignment")
class TicketAssignmentController {

    private final TicketAssignmentService assignments;

    TicketAssignmentController(TicketAssignmentService assignments) {
        this.assignments = assignments;
    }

    /** PUT: the client states the desired ownership; repeating the same request changes nothing. */
    @PutMapping("/api/tickets/{id}/assignment")
    @Operation(
            operationId = "assignTicket",
            summary = "Take, release, assign, reassign or transfer a ticket (requires current version)")
    AssignmentResponse assign(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody AssignmentRequest request) {
        return assignments.assign(user, id, request);
    }
}
