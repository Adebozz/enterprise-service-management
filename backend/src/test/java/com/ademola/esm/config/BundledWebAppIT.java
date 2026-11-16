package com.ademola.esm.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The web app served by the backend itself (single-container hosting). Test resources stand in for
 * the React build the root Dockerfile bundles.
 */
@IntegrationTest
class BundledWebAppIT {

    @Autowired
    MockMvcTester mvc;

    @Test
    void theAppAndItsClientRoutesArePublicAndRevalidated() {
        // "/" is Spring Boot's welcome page: a forward to index.html, which MockMvc records but doesn't follow.
        assertThat(mvc.get().uri("/")).hasStatusOk().hasForwardedUrl("index.html");
        for (String path : new String[] {"/tickets/0199b2c4-6a1e-7c3e-9f00-000000000001", "/admin/sla", "/queue"}) {
            MvcTestResult page = mvc.get().uri(path).exchange();
            assertThat(page).as(path).hasStatusOk();
            assertThat(page).bodyText().contains("<div id=\"root\">");
            assertThat(page.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
        }
    }

    @Test
    void hashedAssetsAreCachedForAYearAndMissingFilesAre404() {
        MvcTestResult asset = mvc.get().uri("/assets/app-test.js").exchange();
        assertThat(asset).hasStatusOk();
        assertThat(asset.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("max-age=31536000")
                .contains("immutable");
        assertThat(mvc.get().uri("/assets/missing.js")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theApiNeverFallsBackToTheAppAndKeepsItsOwnRules() {
        assertThat(mvc.get().uri("/api/tickets")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.UNAUTHORIZED);
        MvcTestResult login = mvc.post()
                .uri("/api/auth/login")
                .contentType("application/json")
                .content("{}")
                .exchange();
        assertThat(login.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void pagesCarryTheSameSecurityHeadersAsNginx() {
        MvcTestResult page = mvc.get().uri("/queue").exchange();

        assertThat(page.getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(page.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(page.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(page.getResponse().getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(page.getResponse().getHeader("Permissions-Policy")).contains("camera=()");
    }
}
