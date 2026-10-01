package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Business rules of user administration, isolated from Spring and the database. (Authorization
 * annotations and SQL behaviour are covered by the integration tests.)
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository users;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    ApplicationEventPublisher events;

    @InjectMocks
    UserService service;

    @Test
    void createNormalisesEmailAndStoresOnlyTheHash() {
        when(passwordEncoder.encode("correct horse battery")).thenReturn("{bcrypt}hash");

        UserResponse created = service.create(
                new CreateUserRequest("  Ada.Lovelace@Example.COM ", " Ada ", Role.AGENT, "correct horse battery"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ada.lovelace@example.com");
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Ada");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(created.email()).isEqualTo("ada.lovelace@example.com");
    }

    @Test
    void createRejectsDuplicateEmailFoundByPreCheck() {
        when(users.existsByEmail("ada@example.com")).thenReturn(true);

        assertErrorCode(
                () -> service.create(new CreateUserRequest("ADA@example.com", "Ada", Role.AGENT, "long enough pw")),
                ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void createTranslatesUniqueConstraintRaceIntoDuplicateEmail() {
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("users_email_uk"));

        assertErrorCode(
                () -> service.create(new CreateUserRequest("ada@example.com", "Ada", Role.AGENT, "long enough pw")),
                ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void createRejectsPasswordsLongerThanBcryptCanHash() {
        // 30 characters, but 90 bytes in UTF-8: passes @Size(max = 72) yet BCrypt would truncate it.
        String password = "密".repeat(30);

        assertErrorCode(
                () -> service.create(new CreateUserRequest("ada@example.com", "Ada", Role.AGENT, password)),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void cannotDemoteTheLastActiveAdmin() {
        User admin = persisted(new User("admin@example.com", "Admin", "h", Role.ADMIN));
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(users.lockActiveUsersWithRole(Role.ADMIN)).thenReturn(List.of(admin));

        assertErrorCode(
                () -> service.update(admin.getId(), new UpdateUserRequest(null, Role.AGENT, null, 0L)),
                ErrorCode.LAST_ADMIN_REQUIRED);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void cannotDeactivateTheLastActiveAdmin() {
        User admin = persisted(new User("admin@example.com", "Admin", "h", Role.ADMIN));
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(users.lockActiveUsersWithRole(Role.ADMIN)).thenReturn(List.of(admin));

        assertErrorCode(
                () -> service.update(admin.getId(), new UpdateUserRequest(null, null, false, 0L)),
                ErrorCode.LAST_ADMIN_REQUIRED);
        assertThat(admin.isActive()).isTrue();
    }

    @Test
    void canDemoteAnAdminWhenAnotherActiveAdminExistsAndPublishesRoleChange() {
        User admin = persisted(new User("admin@example.com", "Admin", "h", Role.ADMIN));
        User otherAdmin = persisted(new User("other@example.com", "Other", "h", Role.ADMIN));
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(users.lockActiveUsersWithRole(Role.ADMIN)).thenReturn(List.of(admin, otherAdmin));

        UserResponse updated = service.update(admin.getId(), new UpdateUserRequest(null, Role.TEAM_LEAD, null, 0L));

        assertThat(updated.role()).isEqualTo(Role.TEAM_LEAD);
        verify(events).publishEvent(new UserRoleChangedEvent(admin.getId(), Role.ADMIN, Role.TEAM_LEAD));
    }

    @Test
    void changingNonAdminRoleDoesNotTakeTheAdminLock() {
        User agent = persisted(new User("agent@example.com", "Agent", "h", Role.AGENT));
        when(users.findById(agent.getId())).thenReturn(Optional.of(agent));

        service.update(agent.getId(), new UpdateUserRequest(null, Role.REQUESTER, null, 0L));

        verify(users, never()).lockActiveUsersWithRole(any());
        verify(events).publishEvent(new UserRoleChangedEvent(agent.getId(), Role.AGENT, Role.REQUESTER));
    }

    @Test
    void settingTheSameRoleIsNotAChange() {
        User agent = persisted(new User("agent@example.com", "Agent", "h", Role.AGENT));
        when(users.findById(agent.getId())).thenReturn(Optional.of(agent));

        service.update(agent.getId(), new UpdateUserRequest(null, Role.AGENT, null, 0L));

        verify(events, never()).publishEvent(any());
    }

    @Test
    void staleVersionIsRejectedBeforeAnyChange() {
        User agent = persisted(new User("agent@example.com", "Agent", "h", Role.AGENT));
        when(users.findById(agent.getId())).thenReturn(Optional.of(agent));

        assertErrorCode(
                () -> service.update(agent.getId(), new UpdateUserRequest("New name", null, null, 7L)),
                ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(agent.getDisplayName()).isEqualTo("Agent");
    }

    @Test
    void unknownUserIsNotFound() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.get(id), ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static User persisted(User user) {
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(code);
    }
}
