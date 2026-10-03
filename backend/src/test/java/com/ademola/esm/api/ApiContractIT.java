package com.ademola.esm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the published API contract ({@code docs/openapi.json}), which the frontend generates its
 * TypeScript types from.
 *
 * <ul>
 *   <li><b>Snapshot:</b> the live spec must equal the committed file, so every API change shows up as
 *       a reviewable diff. To accept a change, regenerate it:
 *       {@code ./mvnw verify -Dit.test=ApiContractIT -Dopenapi.update=true}
 *   <li><b>Conventions:</b> explicit operation ids, summaries, typed errors on every operation, 201
 *       for creation, and response fields marked present.
 * </ul>
 */
@IntegrationTest
class ApiContractIT {

    static final Path SNAPSHOT = Path.of("..", "docs", "openapi.json");
    static final Set<String> PUBLIC = Set.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JsonMapper json;

    JsonNode spec;

    @BeforeEach
    void loadSpec() throws Exception {
        String body = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        spec = json.readTree(body);
    }

    @Test
    void liveContractMatchesTheCommittedSnapshot() throws Exception {
        String pretty = json.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";
        if (Boolean.getBoolean("openapi.update") || !Files.exists(SNAPSHOT)) {
            Files.writeString(SNAPSHOT, pretty);
            return;
        }
        JsonNode committed = json.readTree(Files.readString(SNAPSHOT));
        if (!committed.equals(spec)) {
            Files.writeString(Path.of("target", "openapi.actual.json"), pretty);
            fail("The API contract changed. Review target/openapi.actual.json against docs/openapi.json, then"
                    + " regenerate with: ./mvnw verify -Dit.test=ApiContractIT -Dopenapi.update=true");
        }
    }

    @Test
    void everyOperationHasAUniqueReadableIdAndASummary() {
        List<String> ids = new ArrayList<>();
        forEachOperation((path, method, op) -> {
            String id = op.path("operationId").asString();
            assertThat(id).as("%s %s", method, path).matches("^[a-z][A-Za-z]+$"); // no "create_1"
            assertThat(op.path("summary").asString())
                    .as("%s %s summary", method, path)
                    .isNotBlank();
            ids.add(id);
        });
        assertThat(ids).doesNotHaveDuplicates().hasSizeGreaterThan(25);
    }

    @Test
    void everyOperationDocumentsTypedErrors() {
        forEachOperation((path, method, op) -> {
            JsonNode responses = op.path("responses");
            assertThat(responses
                            .path("default")
                            .path("content")
                            .path("application/problem+json")
                            .path("schema")
                            .path("$ref")
                            .asString())
                    .as("%s %s default error", method, path)
                    .isEqualTo("#/components/schemas/ApiProblem");
            assertThat(responses.has("401")).as("%s %s 401", method, path).isEqualTo(!PUBLIC.contains(path));
        });
    }

    @Test
    void creationEndpointsDocument201() {
        for (String path : List.of(
                "/api/incidents",
                "/api/service-requests",
                "/api/admin/users",
                "/api/admin/teams",
                "/api/admin/categories")) {
            assertThat(spec.path("paths")
                            .path(path)
                            .path("post")
                            .path("responses")
                            .has("201"))
                    .as(path)
                    .isTrue();
        }
    }

    @Test
    void responseFieldsAreAlwaysPresentAndNullabilityIsExplicit() {
        JsonNode summary = schema("TicketSummary");
        assertThat(names(summary.path("required")))
                .isEqualTo(names(summary.path("properties").propertyNames()));
        // Nullable object -> oneOf [ref, null]; nullable scalar -> type [x, null]; non-null stays plain.
        assertThat(summary.path("properties").path("assignee").path("oneOf").toString())
                .contains("NamedRef", "null");
        assertThat(summary.path("properties").path("resolvedAt").path("type").toString())
                .contains("null");
        assertThat(summary.path("properties").path("createdAt").path("type").asString())
                .isEqualTo("string");
        // Request schemas keep validation-derived "required": optional fields may be omitted.
        assertThat(names(schema("CreateIncidentRequest").path("required")))
                .doesNotContain("subcategoryId", "affectedService");
    }

    @Test
    void problemSchemaListsEveryErrorCodeAndMatchesRealErrors() throws Exception {
        JsonNode problem = schema("ApiProblem");
        Set<String> documented = names(problem.path("properties").path("code").path("enum"));
        assertThat(documented)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ErrorCode.values()).map(Enum::name).toList());

        JsonNode realError = json.readTree(
                mvc.get().uri("/api/tickets").exchange().getResponse().getContentAsString());
        assertThat(names(problem.path("properties").propertyNames())).containsAll(names(realError.propertyNames()));
    }

    private JsonNode schema(String name) {
        return spec.path("components").path("schemas").path(name);
    }

    private static Set<String> names(Iterable<?> values) {
        Set<String> result = new TreeSet<>();
        values.forEach(v -> result.add(v instanceof JsonNode node ? node.asString() : v.toString()));
        return result;
    }

    private interface OperationVisitor {
        void visit(String path, String method, JsonNode operation);
    }

    private void forEachOperation(OperationVisitor visitor) {
        Set<String> methods = new HashSet<>(List.of("get", "post", "put", "patch", "delete"));
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (methods.contains(op.getKey())) {
                    visitor.visit(path.getKey(), op.getKey().toUpperCase(), op.getValue());
                }
            }
        }
    }
}
