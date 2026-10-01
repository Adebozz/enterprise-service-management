package com.ademola.esm.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository users;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    AccessTokenService accessTokens;

    @Mock
    RefreshTokenService refreshTokens;

    AuthService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}dummy");
        service = new AuthService(users, passwordEncoder, accessTokens, refreshTokens);
    }

    @Test
    void unknownEmailStillPerformsAPasswordHashComparison() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertInvalidCredentials(() -> service.login("Ghost@Example.com", "whatever-password"));

        // Same expensive work as a real account, so response timing doesn't reveal account existence.
        verify(passwordEncoder).matches("whatever-password", "{bcrypt}dummy");
        verify(refreshTokens, never()).startSession(any());
    }

    @Test
    void wrongPasswordIsRejectedWithTheSameError() {
        User user = user(true);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertInvalidCredentials(() -> service.login("ada@example.com", "wrong"));
    }

    @Test
    void inactiveUserWithCorrectPasswordIsRejectedWithTheSameError() {
        User user = user(false);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("right"), anyString())).thenReturn(true);

        assertInvalidCredentials(() -> service.login("ada@example.com", "right"));
        verify(refreshTokens, never()).startSession(any());
    }

    @Test
    void missingRefreshCookieIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.refresh(null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.refresh(" ")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens, never()).rotate(any());
    }

    private static User user(boolean active) {
        User user = new User("ada@example.com", "Ada", "{bcrypt}real", Role.AGENT);
        ReflectionTestUtils.setField(user, "active", active);
        return user;
    }

    private static void assertInvalidCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .hasMessage("Invalid email or password")
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }
}
