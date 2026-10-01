package com.ademola.esm.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates human-readable references (INC-000001) from per-type PostgreSQL sequences.
 *
 * <p>Race-free by construction: {@code nextval} is atomic across all connections, so concurrent
 * requests can never receive the same number. ("Select the max and add one" is the classic bug here.)
 * A number taken by a transaction that later rolls back is simply skipped.
 */
@Component
public class ReferenceNumberGenerator {

    private final JdbcTemplate jdbc;

    public ReferenceNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(WorkItemType type) {
        // The sequence name is a bound parameter cast to regclass, never concatenated into SQL.
        Long value = jdbc.queryForObject("select nextval(cast(? as regclass))", Long.class, type.sequenceName());
        return format(type, value);
    }

    static String format(WorkItemType type, long value) {
        return "%s-%06d".formatted(type.referencePrefix(), value);
    }
}
