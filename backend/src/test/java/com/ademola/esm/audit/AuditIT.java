package com.ademola.esm.audit;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.IllegalTransactionStateException;

/** The audit trail's guarantees: transactional, attributed, and free of secrets. */
@IntegrationTest
class AuditIT {

    @Autowired
    AuditService auditService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    User admin;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        admin = users.create("admin@example.com", Role.ADMIN);
    }

    @Test
    void auditCannotBeWrittenOutsideABusinessTransaction() {
        assertThatThrownBy(() -> auditService.record(
                        AuditRecord.event(AuditAction.USER_UPDATED, AuditEntityType.USER, UUID.randomUUID(), null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void adminActionsAreAttributedToTheSignedInAdminWithoutSecrets() {
        String token = loginOk(mvc, "admin@example.com").bearer();

        assertThat(createUser(token, "new.agent@example.com")).hasStatus(HttpStatus.CREATED);

        Map<String, Object> row = jdbc.queryForMap(
                "select actor_id, new_value::text as new_value from audit_events where action = 'USER_CREATED'");
        assertThat(row.get("actor_id")).isEqualTo(admin.getId());
        assertThat((String) row.get("new_value"))
                .contains("new.agent@example.com")
                .doesNotContainIgnoringCase("password")
                .doesNotContain("bcrypt");
    }

    @Test
    void failedBusinessOperationLeavesNoAuditEntry() {
        String token = loginOk(mvc, "admin@example.com").bearer();
        createUser(token, "dup@example.com");

        assertThat(createUser(token, "DUP@example.com")).hasStatus(HttpStatus.CONFLICT);

        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'USER_CREATED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void roleChangeRecordsBeforeAndAfterValues() {
        String token = loginOk(mvc, "admin@example.com").bearer();
        User agent = users.create("agent@example.com", Role.AGENT);

        mvc.patch()
                .uri("/api/admin/users/" + agent.getId())
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"TEAM_LEAD\", \"version\": 0}")
                .exchange();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select old_value::text as old, new_value::text as new from audit_events"
                        + " where action = 'USER_UPDATED' and entity_id = ?",
                agent.getId());
        assertThat(rows).hasSize(1);
        assertThat((String) rows.getFirst().get("old")).isEqualTo("{\"role\": \"AGENT\"}");
        assertThat((String) rows.getFirst().get("new")).isEqualTo("{\"role\": \"TEAM_LEAD\"}");
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult createUser(String token, String email) {
        return mvc.post()
                .uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "displayName": "New Agent", "role": "AGENT",
                         "initialPassword": "a-sufficiently-long-pw"}
                        """.formatted(email))
                .exchange();
    }
}
