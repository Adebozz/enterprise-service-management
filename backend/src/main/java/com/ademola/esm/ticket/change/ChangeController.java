package com.ademola.esm.ticket.change;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Changes")
class ChangeController {

    private final ChangeService changeService;

    ChangeController(ChangeService changeService) {
        this.changeService = changeService;
    }

    @PostMapping("/api/changes")
    @Operation(
            operationId = "createChange",
            summary = "Raise a change (support staff), optionally naming the problem it fixes")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<TicketCreatedResponse> create(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateChangeRequest request) {
        TicketCreatedResponse created = changeService.create(user, request);
        return ResponseEntity.created(URI.create("/api/tickets/" + created.id()))
                .body(created);
    }

    @PutMapping("/api/changes/{id}/plan")
    @Operation(
            operationId = "updateChangePlan",
            summary = "Write the plan, until the change is submitted (owning team; requires current version)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updatePlan(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateChangePlanRequest request) {
        changeService.updatePlan(user, id, request);
    }

    @PutMapping("/api/changes/{id}/outcome")
    @Operation(
            operationId = "recordChangeOutcome",
            summary = "Record how the change went (owning team, once implementation has started)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recordOutcome(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody RecordChangeOutcomeRequest request) {
        changeService.recordOutcome(user, id, request);
    }
}
