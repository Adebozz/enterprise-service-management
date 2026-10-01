package com.ademola.esm.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Solves the chicken-and-egg problem "you need an admin to create an admin".
 *
 * <p>On startup, if no active admin exists and bootstrap credentials are configured, one admin is
 * created. It is idempotent: once any active admin exists, it does nothing, so the variables can
 * stay set without effect. If several instances start at once, the email unique constraint lets
 * exactly one succeed.
 *
 * <p>This is a system action, not a user request, so it uses the repository directly instead of the
 * {@code @PreAuthorize}-protected {@link UserService}.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final BootstrapAdminProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    AdminBootstrap(BootstrapAdminProperties properties, UserRepository users, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.isPartiallyConfigured()) {
            log.warn(
                    "Admin bootstrap skipped: both ESM_BOOTSTRAP_ADMIN_EMAIL and ESM_BOOTSTRAP_ADMIN_PASSWORD must be set");
            return;
        }
        if (!properties.isConfigured() || users.existsByRoleAndActiveTrue(Role.ADMIN)) {
            return;
        }
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ESM_BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        UserService.requireBcryptCompatible(properties.password());

        String name =
                properties.displayName() == null || properties.displayName().isBlank()
                        ? "Administrator"
                        : properties.displayName();
        try {
            users.saveAndFlush(
                    new User(properties.email(), name, passwordEncoder.encode(properties.password()), Role.ADMIN));
            log.info("Bootstrap admin account created (no other active admin existed)");
        } catch (DataIntegrityViolationException alreadyCreated) {
            log.info("Bootstrap admin already created by another instance or email in use; skipping");
        }
    }
}
