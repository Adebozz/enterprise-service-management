package com.ademola.esm.ticket.queue;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.user.Role;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Builds the SQL for the ticket list: visibility, view, filters, search, sort and page.
 *
 * <p><b>Injection safety by construction:</b> the SQL text is assembled only from the constant
 * fragments in this class; every value that came from the request is a named bind parameter.
 * Sort fields are looked up in {@link #SORT_COLUMNS}, so a client can never put text into ORDER BY.
 *
 * <p>Pure (no I/O), so the generated SQL is unit-tested, and {@code QueryPlanIT} runs
 * {@code EXPLAIN} on exactly this SQL.
 */
final class TicketListQuery {

    /**
     * Statuses that end a ticket's life. Inlined as literals (not bind parameters) on purpose:
     * PostgreSQL can only use the partial index {@code work_items_unassigned_queue_idx} if the
     * query's predicate textually implies the index's predicate, which it can't prove for a parameter.
     */
    static final String NOT_TERMINAL = "w.status NOT IN ('CLOSED', 'CANCELLED', 'REJECTED')";

    /** API sort property -> SQL expression. The only way client input reaches ORDER BY. */
    static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "w.created_at",
            "updatedAt", "w.updated_at",
            "priority", "w.priority",
            "reference", "w.reference",
            "status", "w.status",
            "title", "lower(w.title)");

    private static final Pattern REFERENCE = Pattern.compile("^(INC|REQ)-0*(\\d{1,10})$", Pattern.CASE_INSENSITIVE);

    private static final String SELECT = """
            SELECT w.id, w.reference, w.type, w.title, w.status, w.priority,
                   w.created_at, w.updated_at, w.first_responded_at, w.resolved_at,
                   w.category_id, c.name AS category_name,
                   w.assigned_team_id, t.name AS team_name,
                   w.assignee_id, a.display_name AS assignee_name,
                   w.requester_id, r.display_name AS requester_name
            FROM work_items w
            JOIN categories c ON c.id = w.category_id
            JOIN teams t ON t.id = w.assigned_team_id
            JOIN users r ON r.id = w.requester_id
            LEFT JOIN users a ON a.id = w.assignee_id
            """;

    record Sql(String text, MapSqlParameterSource params) {}

    /** Ids found by matching the search text against requester and category names (pre-queries). */
    record NameMatches(Collection<UUID> requesterIds, Collection<UUID> categoryIds) {
        static final NameMatches NONE = new NameMatches(List.of(), List.of());
    }

    private final List<String> where = new ArrayList<>();
    private final MapSqlParameterSource params = new MapSqlParameterSource();
    private String fullTextQuery;

    private TicketListQuery() {}

    static TicketListQuery build(CurrentUser user, Set<UUID> userTeams, TicketSearchParams p, NameMatches names) {
        TicketListQuery query = new TicketListQuery();
        query.params.addValue("me", user.id());
        boolean hasTeams = user.role().isStaff() && !userTeams.isEmpty();
        if (hasTeams) {
            query.params.addValue("myTeams", userTeams);
        }
        query.visibility(user, hasTeams);
        query.view(user, p.viewOrDefault(), hasTeams);
        query.filters(p);
        query.search(p.trimmedQuery(), names);
        return query;
    }

    /** Normalises "inc-42" / "INC-000042" to the stored form, or null if q isn't a reference. */
    static String asReference(String q) {
        if (q == null) {
            return null;
        }
        Matcher m = REFERENCE.matcher(q.strip());
        return m.matches()
                ? "%s-%06d".formatted(m.group(1).toUpperCase(Locale.ROOT), Long.parseLong(m.group(2)))
                : null;
    }

    Sql page(Pageable pageable) {
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        return new Sql(SELECT + whereClause() + orderBy(pageable.getSort()) + "\nLIMIT :limit OFFSET :offset", params);
    }

    Sql count() {
        return new Sql("SELECT count(*) FROM work_items w" + whereClause(), params);
    }

    // ----- clauses ------------------------------------------------------------------------------

    /** The SQL form of TicketAccessPolicy: the same rules, applied before any row is loaded. */
    private void visibility(CurrentUser user, boolean hasTeams) {
        if (user.role() == Role.ADMIN) {
            return;
        }
        if (!user.role().isStaff()) {
            where.add("w.requester_id = :me");
        } else if (hasTeams) {
            where.add("(w.requester_id = :me OR w.assignee_id = :me OR w.assigned_team_id IN (:myTeams))");
        } else {
            where.add("(w.requester_id = :me OR w.assignee_id = :me)");
        }
    }

    private void view(CurrentUser user, TicketView view, boolean hasTeams) {
        switch (view) {
            case ALL -> {}
            case REQUESTED -> where.add("w.requester_id = :me");
            case MINE -> where.add("w.assignee_id = :me");
            case TEAM -> where.add(hasTeams ? "w.assigned_team_id IN (:myTeams)" : "false");
            case UNASSIGNED -> {
                where.add("w.assignee_id IS NULL");
                where.add(NOT_TERMINAL);
                if (user.role() != Role.ADMIN) {
                    where.add(hasTeams ? "w.assigned_team_id IN (:myTeams)" : "false");
                }
            }
        }
    }

    private void filters(TicketSearchParams p) {
        if (Boolean.TRUE.equals(p.open()) && p.viewOrDefault() != TicketView.UNASSIGNED) {
            where.add(NOT_TERMINAL);
        }
        if (notEmpty(p.type())) {
            where.add("w.type IN (:types)");
            params.addValue("types", p.type().stream().map(Enum::name).toList());
        }
        if (notEmpty(p.status())) {
            where.add("w.status IN (:statuses)");
            params.addValue("statuses", p.status());
        }
        if (notEmpty(p.priority())) {
            where.add("w.priority IN (:priorities)");
            params.addValue("priorities", p.priority().stream().map(Enum::name).toList());
        }
        if (p.teamId() != null) {
            where.add("w.assigned_team_id = :teamId");
            params.addValue("teamId", p.teamId());
        }
        if (p.assigneeId() != null) {
            where.add("w.assignee_id = :assigneeId");
            params.addValue("assigneeId", p.assigneeId());
        }
        if (p.categoryId() != null) {
            where.add("(w.category_id = :categoryId OR w.subcategory_id = :categoryId)");
            params.addValue("categoryId", p.categoryId());
        }
        if (p.createdFrom() != null) {
            where.add("w.created_at >= :createdFrom");
            params.addValue("createdFrom", startOfDayUtc(p.createdFrom()));
        }
        if (p.createdTo() != null) {
            where.add("w.created_at < :createdToExclusive"); // inclusive day: before the next midnight
            params.addValue("createdToExclusive", startOfDayUtc(p.createdTo().plusDays(1)));
        }
    }

    private void search(String q, NameMatches names) {
        if (q == null) {
            return;
        }
        String reference = asReference(q);
        if (reference != null) {
            where.add("w.reference = :reference");
            params.addValue("reference", reference);
            return;
        }
        fullTextQuery = q;
        params.addValue("q", q);
        List<String> alternatives = new ArrayList<>();
        alternatives.add("w.search_vector @@ websearch_to_tsquery('english', :q)");
        if (!names.requesterIds().isEmpty()) {
            alternatives.add("w.requester_id IN (:qRequesters)");
            params.addValue("qRequesters", names.requesterIds());
        }
        if (!names.categoryIds().isEmpty()) {
            alternatives.add("w.category_id IN (:qCategories)");
            alternatives.add("w.subcategory_id IN (:qCategories)");
            params.addValue("qCategories", names.categoryIds());
        }
        where.add("(" + String.join(" OR ", alternatives) + ")");
    }

    private String whereClause() {
        return where.isEmpty() ? "" : "\nWHERE " + String.join("\n  AND ", where);
    }

    private String orderBy(Sort sort) {
        List<String> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Unsupported sort property: " + order.getProperty());
            }
            orders.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        if (orders.isEmpty()) {
            orders.add(
                    fullTextQuery != null
                            ? "ts_rank(w.search_vector, websearch_to_tsquery('english', :q)) DESC, w.created_at DESC"
                            : "w.created_at DESC");
        }
        orders.add("w.id"); // stable order across pages when sort keys tie
        return "\nORDER BY " + String.join(", ", orders);
    }

    private static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private static java.time.OffsetDateTime startOfDayUtc(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
