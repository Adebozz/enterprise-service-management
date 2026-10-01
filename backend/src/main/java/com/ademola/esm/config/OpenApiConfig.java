package com.ademola.esm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document served at /v3/api-docs and rendered by Swagger UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI esmOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Enterprise Service Management API")
                                .version("v1")
                                .description(
                                        "Incidents, service requests, workflow, assignment and audit for IT service management."));
    }
}
