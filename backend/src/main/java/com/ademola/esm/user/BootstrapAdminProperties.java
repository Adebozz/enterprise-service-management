package com.ademola.esm.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional first-admin credentials, supplied via environment variables
 * ({@code ESM_BOOTSTRAP_ADMIN_EMAIL}, {@code _PASSWORD}, {@code _NAME}). Never committed; in AWS
 * they come from Secrets Manager for the first deployment only.
 */
@ConfigurationProperties("esm.bootstrap.admin")
public record BootstrapAdminProperties(String email, String password, String displayName) {

    boolean isConfigured() {
        return notBlank(email) && notBlank(password);
    }

    boolean isPartiallyConfigured() {
        return !isConfigured() && (notBlank(email) || notBlank(password));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
