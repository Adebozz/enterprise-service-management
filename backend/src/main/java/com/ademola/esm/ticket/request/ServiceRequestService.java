package com.ademola.esm.ticket.request;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import com.ademola.esm.ticket.intake.TicketIntake;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ServiceRequestService {

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestService.class);
    static final Impact DEFAULT_IMPACT = Impact.LOW;
    static final Urgency DEFAULT_URGENCY = Urgency.MEDIUM;

    private final ServiceRequestRepository requests;
    private final TicketIntake intake;

    public ServiceRequestService(ServiceRequestRepository requests, TicketIntake intake) {
        this.requests = requests;
        this.intake = intake;
    }

    @PreAuthorize("isAuthenticated()")
    public TicketCreatedResponse create(CurrentUser requester, CreateServiceRequestRequest request) {
        var draft = intake.prepare(
                requester,
                new TicketIntake.NewTicket(
                        WorkItemType.SERVICE_REQUEST,
                        request.title(),
                        request.description(),
                        request.categoryId(),
                        request.subcategoryId(),
                        DEFAULT_IMPACT,
                        request.urgency() != null ? request.urgency() : DEFAULT_URGENCY));
        ServiceRequest created = requests.save(new ServiceRequest(draft));
        intake.recordCreated(created);
        log.info("Service request created ref={} team={}", created.getReference(), created.getAssignedTeamId());
        return TicketCreatedResponse.from(created);
    }
}
