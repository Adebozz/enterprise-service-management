package com.ademola.esm.ticket.workflow;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import java.util.Collections;
import java.util.Set;

/** One allowed move in a lifecycle, with who may make it and what it requires. */
public record Transition<S extends Enum<S>>(
        S from, S to, String label, Set<Actor> allowedActors, Set<Requirement> requirements) {

    public Transition {
        allowedActors = Set.copyOf(allowedActors);
        requirements = Set.copyOf(requirements);
        if (allowedActors.isEmpty()) {
            throw new IllegalArgumentException("Transition %s -> %s has no allowed actors".formatted(from, to));
        }
    }

    public boolean isAllowedFor(Set<Actor> callerActors) {
        return !Collections.disjoint(allowedActors, callerActors);
    }

    /** Requirements a client must supply in the request (ASSIGNEE is about ticket state, not input). */
    public boolean isSatisfiableBy(boolean ticketHasAssignee) {
        return ticketHasAssignee || !requirements.contains(Requirement.ASSIGNEE);
    }

    /** Throws if the ticket state or the supplied input doesn't meet this transition's requirements. */
    public void checkRequirements(TransitionInput input, boolean ticketHasAssignee) {
        if (requirements.contains(Requirement.ASSIGNEE) && !ticketHasAssignee) {
            throw new BusinessRuleException(
                    ErrorCode.TICKET_NOT_ASSIGNED, "The ticket must be assigned to someone before: " + label);
        }
        if (requirements.contains(Requirement.REASON) && !input.hasReason()) {
            throw missing("a reason");
        }
        if (requirements.contains(Requirement.RESOLUTION) && !(input.hasResolutionCode() && input.hasNotes())) {
            throw missing("a resolution code and resolution notes");
        }
        if (requirements.contains(Requirement.FULFILMENT_NOTES) && !input.hasNotes()) {
            throw missing("fulfilment notes");
        }
    }

    private BusinessRuleException missing(String what) {
        return new BusinessRuleException(
                ErrorCode.TRANSITION_REQUIREMENT_MISSING, "'%s' requires %s".formatted(label, what));
    }
}
