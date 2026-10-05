package com.ademola.esm.config;

import com.ademola.esm.common.error.ApiProblem;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI contract (served at /v3/api-docs, committed as docs/openapi.json).
 *
 * <p>The frontend's TypeScript types are generated from it, so it has to be precise: explicit
 * operation ids (function names), the error body on every operation, response fields marked
 * as always present, and {@code T | null} exactly where a value can be null.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearer-jwt";
    static final String PROBLEM = "ApiProblem";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout");

    static {
        // Free-form JSON (audit before/after values). Without this, swagger-core describes Jackson's
        // JsonNode *class* (isArray, bigDecimal...) instead of "any JSON object".
        SpringDocUtils.getConfig()
                .replaceWithSchema(
                        JsonNode.class,
                        new ObjectSchema().additionalProperties(true).description("Free-form JSON object"));
    }

    @Bean
    OpenAPI esmOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Service Management API")
                        .version("v1")
                        .description(
                                "Incidents, service requests, workflow, assignment, comments and audit for IT service"
                                        + " management. Errors are RFC 9457 problem details (`ApiProblem`); clients should"
                                        + " branch on `code`."))
                // Relative: the SPA and API share an origin (CloudFront in production, a proxy locally).
                .servers(List.of(new Server().url("/").description("Same origin as the web app")))
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

    /**
     * Nullability for {@code @Nullable} properties. swagger-core already marks nullable scalars as
     * {@code type: [string, null]}. OpenAPI 3.1 ignores keywords next to {@code $ref}, so a nullable
     * object reference becomes {@code oneOf: [$ref, null]}; inline replaced schemas get "null" added.
     */
    @Bean
    PropertyCustomizer nullableReferences() {
        return (property, type) -> {
            boolean nullable = type.getCtxAnnotations() != null
                    && Arrays.stream(type.getCtxAnnotations()).anyMatch(a -> a instanceof Nullable);
            if (!nullable) {
                return property;
            }
            if (property.get$ref() != null) {
                Schema<?> wrapped = new Schema<>();
                wrapped.setOneOf(
                        List.of(new Schema<>().$ref(property.get$ref()), new Schema<>().types(Set.of("null"))));
                wrapped.setDescription(property.getDescription());
                return wrapped;
            }
            // Inline schemas (e.g. replaced types such as JsonNode) need "null" added explicitly.
            if (property.getTypes() != null && !property.getTypes().contains("null")) {
                Set<String> types = new java.util.LinkedHashSet<>(property.getTypes());
                types.add("null");
                property.setTypes(types);
            }
            return property;
        };
    }

    @Bean
    OpenApiCustomizer contractConventions() {
        return openApi -> {
            registerProblemSchema(openApi);
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperationsMap()
                            .forEach((method, operation) -> addErrorResponses(path, operation)));
            markResponseFieldsPresent(openApi);
        };
    }

    private static void registerProblemSchema(OpenAPI openApi) {
        ModelConverters.getInstance(true).readAll(ApiProblem.class).forEach(openApi.getComponents()::addSchemas);
    }

    /** Every operation can fail with a problem body; protected ones can also answer 401. */
    private static void addErrorResponses(String path, Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (!PUBLIC_PATHS.contains(path)) {
            responses.addApiResponse("401", problem("Missing, invalid or expired access token"));
        }
        responses.addApiResponse(
                "default",
                problem(
                        "Error: see `code` (validation 400, forbidden 403, not found 404, conflict 409, rule violation 422)"));
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(
                        new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(new Schema<>().$ref(PROBLEM))));
    }

    /**
     * Jackson always writes every field of our response records (null included), so every property
     * of a schema reachable from a response is "required" (present); whether it may be null is
     * expressed separately via {@code @Nullable}. Request schemas keep the required list derived
     * from Bean Validation, because clients may omit optional fields.
     */
    @SuppressWarnings("rawtypes")
    private static void markResponseFieldsPresent(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Deque<String> pending = new ArrayDeque<>();
        openApi.getPaths().values().stream()
                .flatMap(item -> item.readOperations().stream())
                .flatMap(op -> op.getResponses().values().stream())
                .filter(response -> response.getContent() != null)
                .flatMap(response -> response.getContent().values().stream())
                .map(MediaType::getSchema)
                .filter(Objects::nonNull)
                .forEach(schema -> collectRefs(schema, pending));

        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.pop();
            Schema schema = schemas.get(name);
            if (schema == null || !visited.add(name) || schema.getProperties() == null) {
                continue;
            }
            schema.setRequired(new ArrayList<>(
                    new java.util.TreeSet<String>(schema.getProperties().keySet())));
            ((Map<String, Schema>) schema.getProperties()).values().forEach(property -> collectRefs(property, pending));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectRefs(Schema schema, Deque<String> pending) {
        if (schema.get$ref() != null) {
            pending.push(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
        }
        Stream.of(schema.getItems(), schema.getAdditionalProperties() instanceof Schema s ? s : null)
                .filter(Objects::nonNull)
                .forEach(child -> collectRefs((Schema) child, pending));
        if (schema.getOneOf() != null) {
            ((List<Schema>) schema.getOneOf()).forEach(child -> collectRefs(child, pending));
        }
    }
}
