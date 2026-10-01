package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ademola.esm.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    UserRepository users;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    AuditService audit;

    // Runs the callback directly; real transaction behaviour is covered by AdminBootstrapIT.
    TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));

    @Test
    void createsAdminWhenNoneExists() {
        when(users.existsByRoleAndActiveTrue(Role.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("a-long-bootstrap-pw")).thenReturn("{bcrypt}h");
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap(new BootstrapAdminProperties("Root@Example.com", "a-long-bootstrap-pw", "Root"))
                .run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        verify(audit).record(any());
        assertThat(saved.getValue().getEmail()).isEqualTo("root@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}h");
    }

    @Test
    void doesNothingOnceAnyActiveAdminExists() {
        when(users.existsByRoleAndActiveTrue(Role.ADMIN)).thenReturn(true);

        bootstrap(new BootstrapAdminProperties("root@example.com", "a-long-bootstrap-pw", null))
                .run(null);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void doesNothingWhenNotConfigured() {
        bootstrap(new BootstrapAdminProperties(null, null, null)).run(null);

        verify(users, never()).existsByRoleAndActiveTrue(any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void partialConfigurationIsIgnoredRatherThanCreatingABrokenAccount() {
        bootstrap(new BootstrapAdminProperties("root@example.com", "", null)).run(null);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void weakBootstrapPasswordStopsStartup() {
        when(users.existsByRoleAndActiveTrue(Role.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> bootstrap(new BootstrapAdminProperties("root@example.com", "short", null))
                        .run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12");
    }

    private AdminBootstrap bootstrap(BootstrapAdminProperties properties) {
        return new AdminBootstrap(properties, users, passwordEncoder, audit, transaction);
    }
}
