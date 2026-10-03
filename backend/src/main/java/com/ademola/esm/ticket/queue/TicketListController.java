package com.ademola.esm.ticket.queue;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.web.PageResponse;
import com.ademola.esm.common.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Tickets")
class TicketListController {

    private static final SortableFields SORTABLE = new SortableFields(TicketListQuery.SORT_COLUMNS.keySet());

    private final TicketSearchService searchService;

    TicketListController(TicketSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Without an explicit sort: newest first, or by relevance when searching. Use e.g.
     * {@code sort=priority,asc&sort=createdAt,asc} for a work queue.
     */
    @GetMapping("/api/tickets")
    @Operation(summary = "Queues, filters and search over tickets the caller may see (paginated)")
    PageResponse<TicketSummary> list(
            @AuthenticationPrincipal CurrentUser user,
            @Valid @ParameterObject @ModelAttribute TicketSearchParams params,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return searchService.search(user, params, SORTABLE.validate(pageable));
    }
}
