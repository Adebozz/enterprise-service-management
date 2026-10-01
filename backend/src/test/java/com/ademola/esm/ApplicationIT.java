package com.ademola.esm;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Smoke tests: the application boots against real PostgreSQL, migrates, and exposes health probes. */
@IntegrationTest
class ApplicationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedAllMigrationsSuccessfully() {
        Integer failed =
                jdbc.queryForObject("select count(*) from flyway_schema_history where success = false", Integer.class);
        String latest = jdbc.queryForObject(
                "select version from flyway_schema_history where version is not null order by installed_rank desc limit 1",
                String.class);

        assertThat(failed).isZero();
        assertThat(latest).isNotBlank();
    }

    @Test
    void databaseIsPostgres17() {
        String version = jdbc.queryForObject("show server_version", String.class);
        assertThat(version).startsWith("17.");
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void livenessAndReadinessProbesAreExposedForTheLoadBalancer() {
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
    }

    @Test
    void healthDoesNotLeakComponentDetailsToAnonymousCallers() {
        assertThat(mvc.get().uri("/actuator/health")).bodyJson().doesNotHavePath("$.components");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        // Not exposed over HTTP at all; for an anonymous caller the security layer answers first.
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/actuator/beans")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void openApiDocumentIsPublished() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.info.title")
                .isEqualTo("Enterprise Service Management API");
    }
}
