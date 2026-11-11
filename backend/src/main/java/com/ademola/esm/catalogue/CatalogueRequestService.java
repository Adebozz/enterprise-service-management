package com.ademola.esm.catalogue;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import com.ademola.esm.ticket.request.CatalogueSubmission;
import com.ademola.esm.ticket.request.RequestAnswer;
import com.ademola.esm.ticket.request.ServiceRequest;
import com.ademola.esm.ticket.request.ServiceRequestService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordering from the catalogue: check the answers against the item's form, then raise an ordinary
 * service request routed by the item's category. From there it is handled like any other request.
 */
@Service
@Transactional
public class CatalogueRequestService {

    private final CatalogueService catalogue;
    private final ServiceRequestService serviceRequests;

    CatalogueRequestService(CatalogueService catalogue, ServiceRequestService serviceRequests) {
        this.catalogue = catalogue;
        this.serviceRequests = serviceRequests;
    }

    @PreAuthorize("isAuthenticated()")
    public TicketCreatedResponse submit(CurrentUser requester, UUID itemId, SubmitCatalogueRequest request) {
        CatalogueItem item = catalogue.requireOrderable(itemId);
        List<RequestAnswer> answers = CatalogueForm.answer(item.getFields(), request.answersOrEmpty());
        ServiceRequest created = serviceRequests.createFromCatalogue(
                requester,
                new CatalogueSubmission(
                        item.getId(),
                        item.getName(),
                        CatalogueForm.describe(answers, request.notes()),
                        item.getCategoryId(),
                        item.getSubcategoryId(),
                        request.urgency(),
                        answers));
        return TicketCreatedResponse.from(created);
    }
}
