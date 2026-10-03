package com.ademola.esm.ticket.queue;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.web.PageResponse;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.incident.IncidentStatus;
import com.ademola.esm.ticket.request.ServiceRequestStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queues, filtering and search over every ticket the caller may see. */
@Service
@Transactional(readOnly = true)
public class TicketSearchService {

    /** Every status of every ticket type: unknown values are a client error, not an empty result. */
    static final Set<String> KNOWN_STATUSES = Stream.concat(
                    Stream.of(IncidentStatus.values()).map(Enum::name),
                    Stream.of(ServiceRequestStatus.values()).map(Enum::name))
            .collect(Collectors.toUnmodifiableSet());

    private final TicketSearchRepository repository;
    private final TicketAccessPolicy accessPolicy;

    public TicketSearchService(TicketSearchRepository repository, TicketAccessPolicy accessPolicy) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
    }

    @PreAuthorize("isAuthenticated()")
    public PageResponse<TicketSummary> search(CurrentUser user, TicketSearchParams params, Pageable pageable) {
        validate(params);
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        String q = params.trimmedQuery();
        TicketListQuery.NameMatches names = q != null && TicketListQuery.asReference(q) == null
                ? repository.matchNames(q)
                : TicketListQuery.NameMatches.NONE;

        TicketListQuery query = TicketListQuery.build(user, teams, params, names);
        List<TicketSummary> rows = repository.page(query.page(pageable));
        long total = rows.size() < pageable.getPageSize() && pageable.getOffset() == 0
                ? rows.size() // first page not full: no need for a second query
                : repository.count(query.count());
        return PageResponse.from(new PageImpl<>(rows, pageable, total), row -> row);
    }

    private static void validate(TicketSearchParams params) {
        if (params.status() != null) {
            params.status().stream()
                    .filter(status -> !KNOWN_STATUSES.contains(status))
                    .findFirst()
                    .ifPresent(unknown -> {
                        throw new BusinessRuleException(
                                ErrorCode.VALIDATION_FAILED, "Unknown status '%s'".formatted(unknown));
                    });
        }
        if (params.createdFrom() != null
                && params.createdTo() != null
                && params.createdFrom().isAfter(params.createdTo())) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "createdFrom must not be after createdTo");
        }
    }
}
