package com.ademola.esm.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Inserts reference data (teams, memberships, categories) directly with SQL. Tests that exercise
 * ticket behaviour don't need to go through the admin APIs to set the scene; those APIs have their
 * own tests.
 */
@Component
public class Fixtures {

    private final JdbcTemplate jdbc;

    Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID team(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into teams (id, name, created_at, updated_at) values (?, ?, now(), now())", id, name);
        return id;
    }

    public void member(UUID teamId, UUID userId) {
        jdbc.update("insert into team_members (team_id, user_id, joined_at) values (?, ?, now())", teamId, userId);
    }

    public UUID category(String code, String name, UUID parentId, UUID teamId, String appliesTo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into categories (id, code, name, parent_id, default_team_id, applies_to, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, now(), now())",
                id,
                code,
                name,
                parentId,
                teamId,
                appliesTo);
        return id;
    }
}
