package com.ademola.esm.sla;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SLA targets: looked up for new tickets, listed and changed by admins. */
@Service
class SlaPolicyService {

    private final SlaPolicyRepository policies;
    private final AuditService audit;

    SlaPolicyService(SlaPolicyRepository policies, AuditService audit) {
        this.policies = policies;
        this.audit = audit;
    }

    /**
     * The targets for a ticket type and priority.
     *
     * @throws IllegalStateException if the policy row is missing; V8 seeds every combination, so
     *     this means the database was changed outside the application
     */
    @Transactional(readOnly = true)
    SlaTargets targetsFor(WorkItemType type, Priority priority) {
        return policies.findByTicketTypeAndPriority(type, priority)
                .map(SlaPolicy::targets)
                .orElseThrow(() -> new IllegalStateException("No SLA policy for " + type + " " + priority));
    }

    /** Incidents first, then by priority (P1 first). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    List<SlaPolicyResponse> list() {
        return policies.findAll().stream()
                .sorted(Comparator.comparing(SlaPolicy::getTicketType).thenComparing(SlaPolicy::getPriority))
                .map(SlaPolicyResponse::from)
                .toList();
    }

    /**
     * Changes one combination's targets. Tickets already open keep the targets they started with:
     * moving the goalposts under a running clock would rewrite results people already see.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    SlaPolicyResponse update(UUID id, UpdateSlaPolicyRequest request) {
        SlaPolicy policy = policies.findById(id).orElseThrow(() -> new ResourceNotFoundException("SLA policy", id));
        StaleVersionException.check("SLA policy", request.version(), policy.getVersion());
        if (request.responseMinutes() > request.resolutionMinutes()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "The response target can't be longer than the resolution target");
        }

        SlaPolicyResponse before = SlaPolicyResponse.from(policy);
        policy.changeTargets(request.responseMinutes(), request.resolutionMinutes());
        policies.flush(); // return the incremented version
        SlaPolicyResponse after = SlaPolicyResponse.from(policy);
        if (before.responseMinutes() != after.responseMinutes()
                || before.resolutionMinutes() != after.resolutionMinutes()) {
            audit.record(AuditRecord.changed(
                    AuditAction.SLA_POLICY_UPDATED, AuditEntityType.SLA_POLICY, id, targets(before), targets(after)));
        }
        return after;
    }

    private static Map<String, Object> targets(SlaPolicyResponse policy) {
        return Map.of(
                "ticketType", policy.ticketType(),
                "priority", policy.priority(),
                "responseMinutes", policy.responseMinutes(),
                "resolutionMinutes", policy.resolutionMinutes());
    }
}
