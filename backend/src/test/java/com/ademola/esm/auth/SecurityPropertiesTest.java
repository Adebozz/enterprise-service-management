package com.ademola.esm.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The application must refuse to start with a missing or weak signing key. */
class SecurityPropertiesTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    void missingSecretFailsFastWithAHelpfulMessage() {
        assertThatThrownBy(() -> new SecurityProperties.Jwt("", "esm", TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void secretShorterThan256BitsIsRejected() {
        String thirtyOneBytes = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> new SecurityProperties.Jwt(thirtyOneBytes, "esm", TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void nonBase64SecretIsRejected() {
        assertThatThrownBy(() -> new SecurityProperties.Jwt("not base64 !!!", "esm", TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void thirtyTwoByteSecretIsAccepted() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatCode(() -> new SecurityProperties.Jwt(key, "esm", TTL)).doesNotThrowAnyException();
    }
}
