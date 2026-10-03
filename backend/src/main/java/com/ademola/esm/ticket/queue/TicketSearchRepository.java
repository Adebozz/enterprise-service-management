package com.ademola.esm.ticket.queue;

import com.ademola.esm.common.persistence.LikePatterns;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.query.NamedRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Executes the read-model queries built by {@link TicketListQuery}. */
@Repository
class TicketSearchRepository {

    /** Name matches are hints for search, not a result set: cap them to keep the IN lists small. */
    private static final int MAX_NAME_MATCHES = 50;

    private final NamedParameterJdbcTemplate jdbc;

    TicketSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TicketSummary> page(TicketListQuery.Sql sql) {
        return jdbc.query(sql.text(), sql.params(), TicketSearchRepository::toSummary);
    }

    long count(TicketListQuery.Sql sql) {
        Long count = jdbc.queryForObject(sql.text(), sql.params(), Long.class);
        return count == null ? 0 : count;
    }

    TicketListQuery.NameMatches matchNames(String q) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pattern", LikePatterns.containsIgnoringCase(q))
                .addValue("limit", MAX_NAME_MATCHES);
        List<UUID> users = jdbc.queryForList(
                "SELECT id FROM users WHERE lower(display_name) LIKE :pattern OR email LIKE :pattern LIMIT :limit",
                params,
                UUID.class);
        List<UUID> categories = jdbc.queryForList(
                "SELECT id FROM categories WHERE lower(name) LIKE :pattern LIMIT :limit", params, UUID.class);
        return new TicketListQuery.NameMatches(users, categories);
    }

    private static TicketSummary toSummary(ResultSet rs, int row) throws SQLException {
        UUID assigneeId = rs.getObject("assignee_id", UUID.class);
        return new TicketSummary(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                WorkItemType.valueOf(rs.getString("type")),
                rs.getString("title"),
                rs.getString("status"),
                Priority.valueOf(rs.getString("priority")),
                new NamedRef(rs.getObject("category_id", UUID.class), rs.getString("category_name")),
                new NamedRef(rs.getObject("assigned_team_id", UUID.class), rs.getString("team_name")),
                assigneeId == null ? null : new NamedRef(assigneeId, rs.getString("assignee_name")),
                new NamedRef(rs.getObject("requester_id", UUID.class), rs.getString("requester_name")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "first_responded_at"),
                instant(rs, "resolved_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
