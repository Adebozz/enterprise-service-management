package com.ademola.esm.ticket.problem;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Problems")
class ProblemController {

    private final ProblemService problemService;

    ProblemController(ProblemService problemService) {
        this.problemService = problemService;
    }

    @PostMapping("/api/problems")
    @Operation(
            operationId = "createProblem",
            summary = "Raise a problem (support staff), optionally linking the incident that prompted it")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<TicketCreatedResponse> create(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateProblemRequest request) {
        TicketCreatedResponse created = problemService.create(user, request);
        return ResponseEntity.created(URI.create("/api/tickets/" + created.id()))
                .body(created);
    }

    @PutMapping("/api/problems/{id}/analysis")
    @Operation(
            operationId = "updateProblemAnalysis",
            summary = "Record the root cause and workaround (owning team; requires current version)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateAnalysis(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProblemAnalysisRequest request) {
        problemService.updateAnalysis(user, id, request);
    }

    @PutMapping("/api/incidents/{id}/problem")
    @Operation(
            operationId = "linkIncidentToProblem",
            summary = "Link an incident to the open problem behind it (requires the incident's current version)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void link(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody LinkProblemRequest request) {
        problemService.linkIncident(user, id, request);
    }

    @DeleteMapping("/api/incidents/{id}/problem")
    @Operation(
            operationId = "unlinkIncidentFromProblem",
            summary = "Remove an incident's problem link (requires the incident's current version)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id, @RequestParam long version) {
        problemService.unlinkIncident(user, id, version);
    }
}
