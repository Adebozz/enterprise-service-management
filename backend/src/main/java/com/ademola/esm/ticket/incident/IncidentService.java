package com.ademola.esm.ticket.incident;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import com.ademola.esm.ticket.intake.TicketIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final TicketIntake intake;

    public IncidentService(IncidentRepository incidents, TicketIntake intake) {
        this.incidents = incidents;
        this.intake = intake;
    }

    /** Any signed-in user can report an incident; the requester is always the caller. */
    @PreAuthorize("isAuthenticated()")
    public TicketCreatedResponse create(CurrentUser requester, CreateIncidentRequest request) {
        var draft = intake.prepare(
                requester,
                new TicketIntake.NewTicket(
                        WorkItemType.INCIDENT,
                        request.title(),
                        request.description(),
                        request.categoryId(),
                        request.subcategoryId(),
                        request.impact(),
                        request.urgency()));
        Incident incident = incidents.save(new Incident(draft, request.affectedService()));
        intake.recordCreated(incident);
        log.info(
                "Incident created ref={} priority={} team={}",
                incident.getReference(),
                incident.getPriority(),
                incident.getAssignedTeamId());
        return TicketCreatedResponse.from(incident);
    }
}
