package com.ademola.esm.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL (same major version as docker-compose and RDS) for integration tests.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot point the DataSource at the container
 * automatically. Because the container is a bean, it lives as long as the Spring test context, and
 * Spring caches contexts with identical configuration, so all {@link IntegrationTest} classes share
 * one container per test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
