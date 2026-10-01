package com.ademola.esm.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application context + MockMvc + real PostgreSQL via Testcontainers + a controllable clock.
 *
 * <p>All integration tests use this single annotation so they share one cached Spring context (and
 * therefore one database container). Name integration test classes {@code *IT} so Failsafe runs
 * them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        properties = {
            // Test-only signing key (not used anywhere else). Base64 of 32+ bytes.
            "esm.security.jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kdWN0aW9uIQ==",
            "esm.security.allowed-origins=http://localhost:5173",
            // Integration tests must not depend on a developer's local .env bootstrap admin.
            "esm.bootstrap.admin.email=",
            "esm.bootstrap.admin.password="
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public @interface IntegrationTest {}
