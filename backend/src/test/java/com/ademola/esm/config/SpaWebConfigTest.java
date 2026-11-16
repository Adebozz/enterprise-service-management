package com.ademola.esm.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpaWebConfigTest {

    @ParameterizedTest
    @CsvSource({
        "queue, true",
        "tickets/0199b2c4, true",
        "admin/sla, true",
        "'', true",
        "assets/index-abc.js, false",
        "favicon.svg, false",
        "api/tickets, false",
        "actuator/health, false",
        "v3/api-docs, false",
        "swagger-ui/index.html, false"
    })
    void onlyClientRoutesFallBackToTheApp(String path, boolean fallsBack) {
        assertThat(SpaWebConfig.isClientRoute(path)).isEqualTo(fallsBack);
    }
}
