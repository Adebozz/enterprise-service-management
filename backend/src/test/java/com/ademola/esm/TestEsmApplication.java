package com.ademola.esm;

import com.ademola.esm.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application against a throwaway Testcontainers PostgreSQL instead of docker-compose.
 * Useful for quick manual checks: {@code ./mvnw spring-boot:test-run}.
 */
public class TestEsmApplication {

    public static void main(String[] args) {
        SpringApplication.from(EsmApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
