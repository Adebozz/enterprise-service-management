package com.ademola.esm.user;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration.
 *
 * <p>Authorization is declared here, on the service, rather than only on URLs. Any future caller
 * (another controller, a scheduled job, a different API path) gets the same checks.
 */
@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    // Constructor injection: dependencies are explicit, final, and trivially replaceable with mocks
    // in unit tests. No reflection-based field injection.
    public UserService(UserRepository users, PasswordEncoder passwordEncoder, ApplicationEventPublisher events) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse create(CreateUserRequest request) {
        String email = User.normaliseEmail(request.email());
        if (users.existsByEmail(email)) {
            throw emailAlreadyExists();
        }
        requireBcryptCompatible(request.initialPassword());

        User user = new User(
                email, request.displayName(), passwordEncoder.encode(request.initialPassword()), request.role());
        try {
            // Flush now so a concurrent insert of the same email fails here (unique constraint),
            // where we can translate it, instead of at commit time.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException raceLost) {
            throw emailAlreadyExists();
        }
        log.info("User created id={} role={}", user.getId(), user.getRole());
        return UserResponse.from(user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(UserSearchCriteria criteria, Pageable pageable) {
        return PageResponse.from(users.findAll(UserSpecifications.matching(criteria), pageable), UserResponse::from);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(require(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = require(id);
        StaleVersionException.check("User", request.version(), user.getVersion());

        if (wouldRemoveAdminRights(user, request)) {
            ensureAnotherActiveAdminExists(user);
        }
        if (request.displayName() != null) {
            user.rename(request.displayName());
        }
        if (request.role() != null && request.role() != user.getRole()) {
            Role previous = user.getRole();
            user.changeRole(request.role());
            events.publishEvent(new UserRoleChangedEvent(user.getId(), previous, request.role()));
            log.info("User role changed id={} from={} to={}", user.getId(), previous, request.role());
        }
        if (request.active() != null && request.active() != user.isActive()) {
            if (request.active()) {
                user.activate();
            } else {
                user.deactivate();
            }
            log.info("User active flag changed id={} active={}", user.getId(), request.active());
        }

        // Flush before mapping so the response carries the incremented @Version. Otherwise the
        // client would send back a stale version on its next update and get a false conflict.
        users.flush();
        return UserResponse.from(user);
    }

    /** For other modules: loads a user or fails with 404. Callers apply their own authorization. */
    @Transactional(readOnly = true)
    public User require(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private static boolean wouldRemoveAdminRights(User user, UpdateUserRequest request) {
        if (user.getRole() != Role.ADMIN || !user.isActive()) {
            return false;
        }
        boolean demoted = request.role() != null && request.role() != Role.ADMIN;
        boolean deactivated = Boolean.FALSE.equals(request.active());
        return demoted || deactivated;
    }

    private void ensureAnotherActiveAdminExists(User user) {
        boolean anotherAdmin = users.lockActiveUsersWithRole(Role.ADMIN).stream()
                .anyMatch(admin -> !admin.getId().equals(user.getId()));
        if (!anotherAdmin) {
            throw new BusinessRuleException(
                    ErrorCode.LAST_ADMIN_REQUIRED, "The system must always have at least one active administrator");
        }
    }

    private static void requireBcryptCompatible(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Password is too long (maximum 72 bytes when UTF-8 encoded)");
        }
    }

    private static BusinessRuleException emailAlreadyExists() {
        return new BusinessRuleException(ErrorCode.EMAIL_ALREADY_EXISTS, "A user with this email already exists");
    }
}
