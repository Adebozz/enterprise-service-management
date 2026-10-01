package com.ademola.esm.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties every application table between integration tests.
 *
 * <p>We truncate instead of wrapping each test in a rolled-back transaction because many tests need
 * real commits: concurrency tests, constraint checks at flush/commit time, and anything that
 * spans more than one transaction behaves differently inside one big test transaction.
 */
public final class DatabaseCleaner {

    private DatabaseCleaner() {}

    public static void clean(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
    }
}
