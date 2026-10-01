package com.ademola.esm.ticket.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class CategoryApiIT {

    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    UUID networkTeam;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        networkTeam = fixtures.team("Network Team");
    }

    @Test
    void adminBuildsATwoLevelTreeThatRequestersCanBrowseByType() throws Exception {
        String parentId = idOf(create("""
                {"code": "NETWORK", "name": "Network", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(networkTeam)));
        assertThat(create("""
                {"code": "WIFI", "name": "Wi-Fi", "parentId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(parentId))).hasStatus(HttpStatus.CREATED);
        create("""
                {"code": "SOFTWARE", "name": "Software", "defaultTeamId": "%s", "appliesTo": "SERVICE_REQUEST"}
                """.formatted(networkTeam));

        MvcTestResult incidentCategories = mvc.get()
                .uri("/api/categories")
                .param("type", "INCIDENT")
                .with(user("req").roles("REQUESTER"))
                .exchange();

        assertThat(incidentCategories).hasStatusOk();
        assertThat(incidentCategories)
                .bodyJson()
                .extractingPath("$[*].code")
                .asArray()
                .containsExactly("NETWORK");
        assertThat(incidentCategories)
                .bodyJson()
                .extractingPath("$[0].subcategories[*].code")
                .asArray()
                .containsExactly("WIFI");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'CATEGORY_CREATED'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void deactivatedCategoriesDisappearFromTheRequesterView() throws Exception {
        String id = idOf(create("""
                {"code": "NETWORK", "name": "Network", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(networkTeam)));

        assertThat(mvc.patch()
                        .uri("/api/admin/categories/" + id)
                        .with(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false, \"version\": 0}"))
                .hasStatusOk();

        assertThat(mvc.get()
                        .uri("/api/categories")
                        .param("type", "INCIDENT")
                        .with(user("r").roles("REQUESTER")))
                .bodyJson()
                .extractingPath("$")
                .asArray()
                .isEmpty();
    }

    @Test
    void topLevelCategoryWithoutTeamIsRejected() {
        MvcTestResult result = create("""
                {"code": "ORPHAN", "name": "Orphan", "appliesTo": "INCIDENT"}
                """);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_TEAM_REQUIRED");
    }

    @Test
    void treeIsLimitedToTwoLevelsAndSubcategoryScopeMustFitTheParent() throws Exception {
        String parent = idOf(create("""
                {"code": "NETWORK", "name": "Network", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(networkTeam)));
        String child = idOf(create("""
                {"code": "WIFI", "name": "Wi-Fi", "parentId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(parent)));

        MvcTestResult thirdLevel = create("""
                {"code": "WIFI_GUEST", "name": "Guest Wi-Fi", "parentId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(child));
        MvcTestResult broaderScope = create("""
                {"code": "LAN", "name": "LAN", "parentId": "%s", "appliesTo": "ANY"}
                """.formatted(parent));

        assertThat(thirdLevel).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CATEGORY");
        assertThat(broaderScope).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CATEGORY");
    }

    @Test
    void duplicateCodeIsAConflictAndCodeFormatIsValidated() {
        create("""
                {"code": "NETWORK", "name": "Network", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(networkTeam));

        assertThat(create("""
                        {"code": "NETWORK", "name": "Again", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                        """.formatted(networkTeam))).hasStatus(HttpStatus.CONFLICT);
        assertThat(create("""
                        {"code": "not upper", "name": "Bad", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                        """.formatted(networkTeam))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void routingToAnInactiveTeamIsRejected() {
        jdbc.update("update teams set active = false where id = ?", networkTeam);

        MvcTestResult result = create("""
                {"code": "NETWORK", "name": "Network", "defaultTeamId": "%s", "appliesTo": "INCIDENT"}
                """.formatted(networkTeam));

        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TEAM_INACTIVE");
    }

    @Test
    void onlyAdminsManageCategories() {
        assertThat(mvc.post()
                        .uri("/api/admin/categories")
                        .with(user("lead").roles("TEAM_LEAD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult create(String json) {
        return mvc.post()
                .uri("/api/admin/categories")
                .with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static String idOf(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
