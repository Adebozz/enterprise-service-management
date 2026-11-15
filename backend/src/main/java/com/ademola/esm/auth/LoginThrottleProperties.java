package com.ademola.esm.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on failed sign-ins under {@code esm.security.login-throttle}.
 *
 * @param maxFailuresPerAccount failures for one email within the window before it is refused
 *     (stops guessing one person's password)
 * @param maxFailuresPerAddress failures from one client address within the window (stops trying
 *     one common password against many accounts)
 */
@ConfigurationProperties("esm.security.login-throttle")
record LoginThrottleProperties(
        @DefaultValue("5") int maxFailuresPerAccount,
        @DefaultValue("20") int maxFailuresPerAddress,
        @DefaultValue("15m") Duration window) {

    LoginThrottleProperties {
        if (maxFailuresPerAccount < 1 || maxFailuresPerAddress < 1 || !window.isPositive()) {
            throw new IllegalArgumentException("esm.security.login-throttle limits must be positive");
        }
    }
}
