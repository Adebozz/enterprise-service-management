package com.ademola.esm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database is the last line of defence: these tests bypass the application with raw SQL and
 * prove the constraints in V2 hold even if service-layer checks are skipped or buggy.
 */
@IntegrationTest
class UserTeamSchemaIT {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void emailMustBeUniqueAndNormalised() {
        insertUser("ada@example.com", "AGENT");

        assertThatThrownBy(() -> insertUser("ada@example.com", "AGENT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_email_uk");
        assertThatThrownBy(() -> insertUser("Ada@Example.com", "AGENT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_email_normalised_check");
        assertThatThrownBy(() -> insertUser(" bob@example.com", "AGENT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_email_normalised_check");
    }

    @Test
    void roleMustBeAKnownValue() {
        assertThatThrownBy(() -> insertUser("x@example.com", "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_role_check");
    }

    @Test
    void teamNamesAreUniqueIgnoringCase() {
        insertTeam("Network Team");

        assertThatThrownBy(() -> insertTeam("NETWORK team"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("teams_name_uk");
    }

    @Test
    void membershipIsUniquePerTeamAndUser() {
        UUID team = insertTeam("Network Team");
        UUID user = insertUser("agent@example.com", "AGENT");
        insertMembership(team, user);

        assertThatThrownBy(() -> insertMembership(team, user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("team_members_pk");
    }

    @Test
    void membershipRequiresExistingTeamAndUser() {
        UUID team = insertTeam("Network Team");
        UUID user = insertUser("agent@example.com", "AGENT");

        assertThatThrownBy(() -> insertMembership(team, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMembership(UUID.randomUUID(), user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertMembership(team, user)).doesNotThrowAnyException();
    }

    @Test
    void usersWithMembershipsCannotBeHardDeleted() {
        UUID team = insertTeam("Network Team");
        UUID user = insertUser("agent@example.com", "AGENT");
        insertMembership(team, user);

        assertThatThrownBy(() -> jdbc.update("delete from users where id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into users (id, email, display_name, password_hash, role, created_at, updated_at)"
                        + " values (?, ?, 'Test', 'x', ?, now(), now())",
                id,
                email,
                role);
        return id;
    }

    private UUID insertTeam(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into teams (id, name, created_at, updated_at) values (?, ?, now(), now())", id, name);
        return id;
    }

    private void insertMembership(UUID team, UUID user) {
        jdbc.update("insert into team_members (team_id, user_id, joined_at) values (?, ?, now())", team, user);
    }
}
