package com.ademola.esm.ticket.queue;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.web.PageResponse;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.incident.IncidentStatus;
import com.ademola.esm.ticket.request.ServiceRequestStatus;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
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
    private final Clock clock;
    private final double slaAtRiskThreshold;

    /**
     * @param slaAtRiskThreshold the same setting the {@code sla} module validates and uses for a
     *     ticket's SLA details, read here by key because {@code ticket} doesn't depend on {@code sla}
     */
    public TicketSearchService(
            TicketSearchRepository repository,
            TicketAccessPolicy accessPolicy,
            Clock clock,
            @Value("${esm.sla.at-risk-threshold}") double slaAtRiskThreshold) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.slaAtRiskThreshold = slaAtRiskThreshold;
    }

    @PreAuthorize("isAuthenticated()")
    public PageResponse<TicketSummary> search(CurrentUser user, TicketSearchParams params, Pageable pageable) {
        validate(params);
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        String q = params.trimmedQuery();
        TicketListQuery.NameMatches names = q != null && TicketListQuery.asReference(q) == null
                ? repository.matchNames(q)
                : TicketListQuery.NameMatches.NONE;

        TicketListQuery query =
                TicketListQuery.build(user, teams, params, names).slaAsOf(clock.instant(), slaAtRiskThreshold);
        List<TicketSummary> rows = repository.page(query.page(pageable));
        if (!user.role().isStaff()) {
            rows = rows.stream().map(TicketSummary::withoutSla).toList(); // service levels are internal
        }
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
