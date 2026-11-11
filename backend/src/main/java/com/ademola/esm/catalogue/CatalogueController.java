package com.ademola.esm.catalogue;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Service catalogue")
class CatalogueController {

    private final CatalogueService catalogue;
    private final CatalogueRequestService requests;

    CatalogueController(CatalogueService catalogue, CatalogueRequestService requests) {
        this.catalogue = catalogue;
        this.requests = requests;
    }

    @GetMapping("/api/catalogue")
    @Operation(operationId = "listCatalogue", summary = "Items requesters can order, with their forms")
    List<CatalogueItemResponse> list() {
        return catalogue.listActive();
    }

    @GetMapping("/api/catalogue/{id}")
    @Operation(operationId = "getCatalogueItem", summary = "One catalogue item and its form")
    CatalogueItemResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return catalogue.get(user, id);
    }

    @PostMapping("/api/catalogue/{id}/requests")
    @Operation(
            operationId = "orderCatalogueItem",
            summary = "Order an item: answers are checked against its form and a service request is raised")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<TicketCreatedResponse> order(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody SubmitCatalogueRequest request) {
        TicketCreatedResponse created = requests.submit(user, id, request);
        return ResponseEntity.created(URI.create("/api/tickets/" + created.id()))
                .body(created);
    }

    @GetMapping("/api/admin/catalogue")
    @Operation(operationId = "listAllCatalogueItems", summary = "Every catalogue item, including withdrawn ones")
    List<CatalogueItemResponse> listAll() {
        return catalogue.listAll();
    }

    @PostMapping("/api/admin/catalogue")
    @Operation(operationId = "createCatalogueItem", summary = "Add an item to the catalogue")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<CatalogueItemResponse> create(@Valid @RequestBody CreateCatalogueItemRequest request) {
        CatalogueItemResponse created = catalogue.create(request);
        return ResponseEntity.created(URI.create("/api/catalogue/" + created.id()))
                .body(created);
    }

    @PutMapping("/api/admin/catalogue/{id}")
    @Operation(
            operationId = "updateCatalogueItem",
            summary = "Replace an item's details and form, or withdraw it (requires current version)")
    CatalogueItemResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCatalogueItemRequest request) {
        return catalogue.update(id, request);
    }
}
