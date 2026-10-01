package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    UserRepository users;

    @Mock
    PasswordEncoder passwordEncoder;

    @Test
    void createsAdminWhenNoneExists() {
        when(users.existsByRoleAndActiveTrue(Role.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("a-long-bootstrap-pw")).thenReturn("{bcrypt}h");

        bootstrap(new BootstrapAdminProperties("Root@Example.com", "a-long-bootstrap-pw", "Root"))
                .run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
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
        return new AdminBootstrap(properties, users, passwordEncoder);
    }
}
