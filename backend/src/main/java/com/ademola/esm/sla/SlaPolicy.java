package com.ademola.esm.sla;

import com.ademola.esm.common.persistence.BaseEntity;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;

/**
 * Response and resolution targets for one ticket type and priority.
 *
 * <p>There is exactly one row per combination, seeded by the V8 migration. Rows are reference data:
 * admins may change the numbers, but never add or remove combinations, so every ticket always finds
 * its policy.
 */
@Entity
@Table(name = "sla_policies")
class SlaPolicy extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, updatable = false, length = 20)
    private WorkItemType ticketType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 2)
    private Priority priority;

    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    protected SlaPolicy() {}

    WorkItemType getTicketType() {
        return ticketType;
    }

    Priority getPriority() {
        return priority;
    }

    /** Bounds are checked by the caller and enforced again by the table's CHECK constraint. */
    void changeTargets(int responseMinutes, int resolutionMinutes) {
        this.responseMinutes = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
    }

    SlaTargets targets() {
        return new SlaTargets(Duration.ofMinutes(responseMinutes), Duration.ofMinutes(resolutionMinutes));
    }
}
