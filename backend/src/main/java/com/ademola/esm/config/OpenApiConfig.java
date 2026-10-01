package com.ademola.esm.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served at /v3/api-docs and rendered by Swagger UI at /swagger-ui.html.
 * Declares bearer-JWT authentication so Swagger UI shows an "Authorize" button.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI esmOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Enterprise Service Management API")
                                .version("v1")
                                .description(
                                        "Incidents, service requests, workflow, assignment and audit for IT service management."))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Access token from POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
