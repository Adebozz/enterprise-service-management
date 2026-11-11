package com.ademola.esm.ticket.comment;

import com.ademola.esm.ticket.TicketChangedEvent;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.incident.Incident;
import com.ademola.esm.ticket.incident.IncidentRepository;
import com.ademola.esm.ticket.incident.IncidentStatus;
import com.ademola.esm.ticket.problem.Problem;
import com.ademola.esm.ticket.problem.ProblemRepository;
import com.ademola.esm.ticket.problem.ProblemStatus;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A fixed problem tells the incidents it explained, with an internal note on each, so whoever
 * handles them can check and resolve them. Incidents already resolved or finished are left alone.
 *
 * <p>Lives with the conversation it writes to, so {@code problem} needn't depend on {@code comment}.
 */
@Component
class ProblemFixedNotes {

    private final ProblemRepository problems;
    private final IncidentRepository incidents;
    private final CommentService comments;

    ProblemFixedNotes(ProblemRepository problems, IncidentRepository incidents, CommentService comments) {
        this.problems = problems;
        this.incidents = incidents;
        this.comments = comments;
    }

    /** Runs in the transition's transaction, so the notes and the fix commit together. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(TicketChangedEvent event) {
        if (event.created()
                || event.type() != WorkItemType.PROBLEM
                || !ProblemStatus.RESOLVED.name().equals(event.status())) {
            return;
        }
        Problem problem = problems.getReferenceById(event.ticketId());
        // Resolving needs an owner, so the assignee is who fixed it.
        UUID author = problem.getAssigneeId();
        String note = "Problem %s has been fixed: %s%nIf this incident is no longer happening, it can be resolved."
                .formatted(problem.getReference(), problem.getFixNotes());
        for (Incident incident : incidents.findByProblemIdOrderByCreatedAtAsc(problem.getId())) {
            if (incident.getStatus() != IncidentStatus.RESOLVED && !incident.isTerminal()) {
                comments.recordInternalNote(incident, author, note);
            }
        }
    }
}
