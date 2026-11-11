package com.ademola.esm.sla;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Looks up the SLA targets that apply to a ticket. */
@Service
class SlaPolicyService {

    private final SlaPolicyRepository policies;

    SlaPolicyService(SlaPolicyRepository policies) {
        this.policies = policies;
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
}
