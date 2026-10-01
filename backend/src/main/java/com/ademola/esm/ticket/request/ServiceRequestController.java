package com.ademola.esm.ticket.request;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/service-requests")
@Tag(name = "Service requests")
class ServiceRequestController {

    private final ServiceRequestService serviceRequestService;

    ServiceRequestController(ServiceRequestService serviceRequestService) {
        this.serviceRequestService = serviceRequestService;
    }

    @PostMapping
    @Operation(summary = "Submit a service request; it is routed to a team by category")
    ResponseEntity<TicketCreatedResponse> create(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateServiceRequestRequest request) {
        TicketCreatedResponse created = serviceRequestService.create(user, request);
        return ResponseEntity.created(URI.create("/api/tickets/" + created.id()))
                .body(created);
    }
}
