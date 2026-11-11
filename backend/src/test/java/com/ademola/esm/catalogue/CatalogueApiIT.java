package com.ademola.esm.catalogue;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The service catalogue: maintaining items, browsing them and ordering from them. */
@IntegrationTest
class CatalogueApiIT {

    static final String LAPTOP_FORM = """
            [
              {"key": "model", "label": "Which model?", "type": "SELECT", "required": true,
               "options": ["Standard", "Developer"]},
              {"key": "neededBy", "label": "Needed by", "type": "DATE", "required": false},
              {"key": "reason", "label": "Why do you need it?", "type": "LONG_TEXT", "required": true,
               "helpText": "A sentence is enough"}
            ]""";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    Fixtures fixtures;

    UUID team;
    UUID hardware;
    UUID laptop;
    UUID network;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        team = fixtures.team("Hardware Team");
        hardware = fixtures.category("HARDWARE", "Hardware", null, team, "ANY");
        laptop = fixtures.category("LAPTOP", "Laptop", hardware, null, "ANY");
        network = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        users.create("requester@example.com", Role.REQUESTER);
        users.create("agent@example.com", Role.AGENT);
        users.create("admin@example.com", Role.ADMIN);
    }

    @Test
    void anAdminPublishesAnItemThatRequestersCanBrowse() {
        MvcTestResult created = createItem("New laptop", LAPTOP_FORM);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = JsonPath.read(body(created), "$.id");
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/catalogue/" + id);
        assertThat(created).bodyJson().extractingPath("$.category.name").isEqualTo("Hardware");
        assertThat(created).bodyJson().extractingPath("$.subcategory.name").isEqualTo("Laptop");
        assertThat(created).bodyJson().extractingPath("$.fields[0].options[1]").isEqualTo("Developer");
        assertThat(created)
                .bodyJson()
                .extractingPath("$.fields[1].options")
                .asArray()
                .isEmpty();
        assertThat(created).bodyJson().extractingPath("$.fields[2].helpText").isEqualTo("A sentence is enough");

        MvcTestResult list = get("requester@example.com", "/api/catalogue");
        assertThat(list).hasStatusOk();
        assertThat(list).bodyJson().extractingPath("$[0].name").isEqualTo("New laptop");
        assertThat(get("requester@example.com", "/api/catalogue/" + id)).hasStatusOk();
        assertThat(audit("CATALOGUE_ITEM_CREATED")).isEqualTo(1);
    }

    @Test
    void orderingRaisesARoutedServiceRequestThatKeepsTheAnswers() {
        String item = id(createItem("New laptop", LAPTOP_FORM));

        MvcTestResult ordered = order(item, """
                {"answers": {"model": "Developer", "reason": " Mine is five years old "},
                 "notes": "Desk 4, second floor", "urgency": "HIGH"}""");

        assertThat(ordered).hasStatus(HttpStatus.CREATED);
        assertThat(ordered).bodyJson().extractingPath("$.reference").asString().startsWith("REQ-");
        assertThat(ordered).bodyJson().extractingPath("$.status").isEqualTo("SUBMITTED");
        String ticket = JsonPath.read(body(ordered), "$.id");

        MvcTestResult view = get("requester@example.com", "/api/tickets/" + ticket);
        assertThat(view).bodyJson().extractingPath("$.title").isEqualTo("New laptop");
        assertThat(view)
                .bodyJson()
                .extractingPath("$.description")
                .isEqualTo(
                        "Which model?: Developer\nWhy do you need it?: Mine is five years old\n\nDesk 4, second floor");
        assertThat(view).bodyJson().extractingPath("$.urgency").isEqualTo("HIGH");
        assertThat(view).bodyJson().extractingPath("$.assignedTeam.name").isEqualTo("Hardware Team");
        assertThat(view).bodyJson().extractingPath("$.subcategory.name").isEqualTo("Laptop");
        assertThat(view)
                .bodyJson()
                .extractingPath("$.serviceRequest.catalogueItemId")
                .isEqualTo(item);
        assertThat(view)
                .bodyJson()
                .extractingPath("$.serviceRequest.answers[*].label")
                .isEqualTo(List.of("Which model?", "Why do you need it?"));
        assertThat(view)
                .bodyJson()
                .extractingPath("$.serviceRequest.answers[1].value")
                .isEqualTo("Mine is five years old");
    }

    @Test
    void badAnswersAreReportedPerQuestionAndNothingIsRaised() {
        String item = id(createItem("New laptop", LAPTOP_FORM));

        MvcTestResult result = order(item, """
                {"answers": {"model": "Gaming rig", "neededBy": "soon", "colour": "red"}}""");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATALOGUE_ANSWERS_INVALID");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.fieldErrors[*].field")
                .isEqualTo(List.of("answers.model", "answers.neededBy", "answers.reason", "answers.colour"));
        assertThat(jdbc.queryForObject("select count(*) from work_items", Integer.class))
                .isZero();
    }

    @Test
    void anItemWithoutQuestionsCanBeOrderedWithNoBody() {
        String item = id(createItem("Replacement mouse", "[]"));

        MvcTestResult ordered = order(item, "{}");

        assertThat(ordered).hasStatus(HttpStatus.CREATED);
        String ticket = JsonPath.read(body(ordered), "$.id");
        assertThat(get("requester@example.com", "/api/tickets/" + ticket))
                .bodyJson()
                .extractingPath("$.description")
                .isEqualTo("Ordered from the service catalogue.");
    }

    @Test
    void onlyAdminsMaintainTheCatalogue() {
        assertThat(post("agent@example.com", "/api/admin/catalogue", itemJson("Laptop", "[]")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("requester@example.com", "/api/admin/catalogue")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void itemsAreCheckedBeforeTheyAreSaved() {
        createItem("New laptop", "[]");

        MvcTestResult duplicate = createItem("NEW LAPTOP", "[]");
        assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
        assertThat(duplicate).bodyJson().extractingPath("$.code").isEqualTo("CATALOGUE_ITEM_NAME_ALREADY_EXISTS");

        MvcTestResult badForm = createItem("Monitor", """
                [{"key": "size", "label": "Size", "type": "SELECT", "required": true, "options": ["24in"]}]""");
        assertThat(badForm).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(badForm).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CATALOGUE_FORM");
        assertThat(badForm).bodyJson().extractingPath("$.fieldErrors[0].field").isEqualTo("fields[0].options");

        MvcTestResult badKey = createItem("Monitor", """
                [{"key": "Screen size!", "label": "Size", "type": "TEXT", "required": true}]""");
        assertThat(badKey).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(badKey).bodyJson().extractingPath("$.fieldErrors[0].field").isEqualTo("fields[0].key");

        MvcTestResult incidentOnly = post("admin@example.com", "/api/admin/catalogue", """
                {"name": "VPN", "summary": "s", "categoryId": "%s", "fields": []}""".formatted(network));
        assertThat(incidentOnly).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(incidentOnly).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CATEGORY");
    }

    @Test
    void aWithdrawnItemDisappearsForRequestersButNotForAdmins() {
        String item = id(createItem("New laptop", LAPTOP_FORM));

        MvcTestResult withdrawn = update(item, "New laptop", LAPTOP_FORM, false, 0);
        assertThat(withdrawn).hasStatusOk();
        assertThat(withdrawn).bodyJson().extractingPath("$.active").isEqualTo(false);
        assertThat(withdrawn).bodyJson().extractingPath("$.version").isEqualTo(1);

        assertThat(get("requester@example.com", "/api/catalogue")).bodyJson().isEqualTo("[]");
        assertThat(get("requester@example.com", "/api/catalogue/" + item)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(order(item, "{}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get("admin@example.com", "/api/catalogue/" + item)).hasStatusOk();
        assertThat(get("admin@example.com", "/api/admin/catalogue"))
                .bodyJson()
                .extractingPath("$[0].active")
                .isEqualTo(false);
        assertThat(jdbc.queryForObject(
                        "select new_value->>'active' from audit_events where action = 'CATALOGUE_ITEM_UPDATED'",
                        String.class))
                .isEqualTo("false");
    }

    @Test
    void editingAFormDoesNotRewriteRequestsAlreadyRaised() {
        String item = id(createItem("New laptop", LAPTOP_FORM));
        String ticket = JsonPath.read(
                body(order(item, "{\"answers\": {\"model\": \"Standard\", \"reason\": \"New starter\"}}")), "$.id");

        String renamed = LAPTOP_FORM.replace("Which model?", "Laptop model");
        assertThat(update(item, "New laptop", renamed, true, 0)).hasStatusOk();

        assertThat(get("requester@example.com", "/api/tickets/" + ticket))
                .bodyJson()
                .extractingPath("$.serviceRequest.answers[0].label")
                .isEqualTo("Which model?");
    }

    @Test
    void updatesNeedTheCurrentVersion() {
        String item = id(createItem("New laptop", "[]"));

        MvcTestResult stale = update(item, "Laptop", "[]", true, 5);

        assertThat(stale).hasStatus(HttpStatus.CONFLICT);
        assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void renamingOntoAnotherItemsNameIsRefused() {
        createItem("New laptop", "[]");
        String monitor = id(createItem("Monitor", "[]"));

        assertThat(update(monitor, "new laptop", "[]", true, 0)).hasStatus(HttpStatus.CONFLICT);
        assertThat(update(monitor, "Monitor (27in)", "[]", true, 0)).hasStatusOk();
    }

    // ----- helpers ------------------------------------------------------------------------------

    private MvcTestResult createItem(String name, String fields) {
        return post("admin@example.com", "/api/admin/catalogue", itemJson(name, fields));
    }

    private String itemJson(String name, String fields) {
        return """
                {"name": "%s", "summary": "Order a %s", "description": "Delivered within a week.",
                 "categoryId": "%s", "subcategoryId": "%s", "fields": %s}""".formatted(name, name, hardware, laptop, fields);
    }

    private MvcTestResult update(String id, String name, String fields, boolean active, long version) {
        return mvc.put()
                .uri("/api/admin/catalogue/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token("admin@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "summary": "Order one", "categoryId": "%s", "subcategoryId": "%s",
                         "fields": %s, "active": %s, "version": %d}""".formatted(name, hardware, laptop, fields, active, version))
                .exchange();
    }

    private MvcTestResult order(String item, String json) {
        return post("requester@example.com", "/api/catalogue/" + item + "/requests", json);
    }

    private MvcTestResult get(String email, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .exchange();
    }

    private MvcTestResult post(String email, String uri, String json) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token(email))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private Integer audit(String action) {
        return jdbc.queryForObject("select count(*) from audit_events where action = ?", Integer.class, action);
    }

    private String token(String email) {
        return loginOk(mvc, email).bearer();
    }

    private static String id(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
