package com.ademola.esm.ticket.incident;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents")
class IncidentController {

    private final IncidentService incidentService;

    IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    @Operation(
            operationId = "createIncident",
            summary = "Report an incident. Priority is calculated and the ticket is routed to a team")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<TicketCreatedResponse> create(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateIncidentRequest request) {
        TicketCreatedResponse created = incidentService.create(user, request);
        return ResponseEntity.created(URI.create("/api/tickets/" + created.id()))
                .body(created);
    }
}
