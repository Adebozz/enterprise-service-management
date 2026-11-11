package com.ademola.esm.sla;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import java.util.UUID;

record SlaPolicyResponse(
        UUID id, WorkItemType ticketType, Priority priority, int responseMinutes, int resolutionMinutes, long version) {

    static SlaPolicyResponse from(SlaPolicy policy) {
        SlaTargets targets = policy.targets();
        return new SlaPolicyResponse(
                policy.getId(),
                policy.getTicketType(),
                policy.getPriority(),
                Math.toIntExact(targets.response().toMinutes()),
                Math.toIntExact(targets.resolution().toMinutes()),
                policy.getVersion());
    }
}
