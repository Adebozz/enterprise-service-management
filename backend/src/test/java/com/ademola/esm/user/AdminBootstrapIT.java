package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ademola.esm.audit.AuditService;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Bootstrap against the real database and transaction manager. */
@IntegrationTest
class AdminBootstrapIT {

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AuditService audit;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void createsTheFirstAdminAndAuditsItAsASystemAction() {
        bootstrap("root@example.com").run(null);

        assertThat(users.findByEmail("root@example.com"))
                .get()
                .extracting(User::getRole)
                .isEqualTo(Role.ADMIN);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'USER_CREATED' and actor_id is null",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void losingTheInsertRaceDoesNotAbortStartup() {
        // Simulates another instance having taken the email between our check and our insert.
        testUsers.create("root@example.com", Role.AGENT);

        assertThatCode(() -> bootstrap("root@example.com").run(null)).doesNotThrowAnyException();
        assertThat(users.findByEmail("root@example.com"))
                .get()
                .extracting(User::getRole)
                .isEqualTo(Role.AGENT);
    }

    private AdminBootstrap bootstrap(String email) {
        return new AdminBootstrap(
                new BootstrapAdminProperties(email, "bootstrap-password-123", "Root"),
                users,
                passwordEncoder,
                audit,
                transaction);
    }
}
