package com.ademola.esm.sla;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Service levels")
class SlaController {

    private final SlaQueryService queries;
    private final SlaPolicyService policies;

    SlaController(SlaQueryService queries, SlaPolicyService policies) {
        this.queries = queries;
        this.policies = policies;
    }

    @GetMapping("/api/tickets/{id}/sla")
    @Operation(
            operationId = "getTicketSla",
            summary = "Response and resolution clocks of the ticket (support staff handling it, and admins)")
    TicketSlaResponse ticketSla(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return queries.forTicket(user, id);
    }

    @GetMapping("/api/admin/sla-policies")
    @Operation(operationId = "listSlaPolicies", summary = "SLA targets for every ticket type and priority")
    List<SlaPolicyResponse> listPolicies() {
        return policies.list();
    }

    @PutMapping("/api/admin/sla-policies/{id}")
    @Operation(
            operationId = "updateSlaPolicy",
            summary = "Change the SLA targets of one type and priority (applies to tickets raised afterwards)")
    SlaPolicyResponse updatePolicy(@PathVariable UUID id, @Valid @RequestBody UpdateSlaPolicyRequest request) {
        return policies.update(id, request);
    }
}
