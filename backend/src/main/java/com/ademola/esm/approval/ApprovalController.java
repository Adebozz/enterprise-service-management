package com.ademola.esm.approval;

import com.ademola.esm.auth.CurrentUser;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Approvals")
class ApprovalController {

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/api/approvals")
    @Operation(
            operationId = "listMyPendingApprovals",
            summary = "Requests waiting for the caller's approval (team leads of the request's team, and admins)")
    List<PendingApprovalResponse> pending(@AuthenticationPrincipal CurrentUser user) {
        return approvals.pendingFor(user);
    }

    @GetMapping("/api/tickets/{id}/approval")
    @Operation(
            operationId = "getTicketApproval",
            summary = "The ticket's approval, for anyone who can see the ticket (404 if it needs none)")
    ApprovalResponse forTicket(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return approvals.forTicket(user, id);
    }

    @PostMapping("/api/approvals/{id}/decision")
    @Operation(
            operationId = "decideApproval",
            summary = "Approve or reject (a reason is required to reject; requires current version)")
    ApprovalResponse decide(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return approvals.decide(user, id, request);
    }
}
