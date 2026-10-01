package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@WithMockUser(roles = "ADMIN")
class AdminUserApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void adminCreatesUserAndResponseNeverContainsPasswordData() {
        MvcTestResult result = createUser("""
                {"email": "Grace.Hopper@Example.com", "displayName": "Grace Hopper",
                 "role": "AGENT", "initialPassword": "a-sufficiently-long-pw"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("Location", "/api/admin/users/" + idOf(result));
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("grace.hopper@example.com");
        assertThat(result).bodyJson().extractingPath("$.version").isEqualTo(0);
        assertThat(result).bodyText().doesNotContainIgnoringCase("password");

        User stored = users.findByEmail("grace.hopper@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("a-sufficiently-long-pw");
        assertThat(passwordEncoder.matches("a-sufficiently-long-pw", stored.getPasswordHash()))
                .isTrue();
    }

    @Test
    void duplicateEmailInDifferentCaseIsAConflict() {
        createUser(agentJson("grace@example.com"));

        MvcTestResult result = createUser(agentJson("GRACE@example.com"));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void invalidCreateRequestReportsEachField() {
        MvcTestResult result = createUser("""
                {"email": "not-an-email", "displayName": "", "initialPassword": "short"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.fieldErrors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("email", "displayName", "role", "initialPassword");
    }

    @Test
    void searchFiltersPaginatesAndSorts() {
        users.save(new User("alice@example.com", "Alice", "h", Role.AGENT));
        users.save(new User("bob@example.com", "Bob", "h", Role.AGENT));
        users.save(new User("carol@example.com", "Carol", "h", Role.REQUESTER));
        users.save(new User("dan@corp.example.com", "Dan", "h", Role.AGENT));

        MvcTestResult result = mvc.get()
                .uri("/api/admin/users")
                .param("role", "AGENT")
                .param("q", "@example.com")
                .param("size", "1")
                .param("page", "1")
                .param("sort", "displayName,desc")
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.totalElements").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.totalPages").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.content[0].displayName").isEqualTo("Alice");
    }

    @Test
    void searchTreatsLikeWildcardsLiterally() {
        users.save(new User("percent@example.com", "100% Done", "h", Role.AGENT));
        users.save(new User("other@example.com", "Other", "h", Role.AGENT));

        assertThat(mvc.get().uri("/api/admin/users").param("q", "%"))
                .bodyJson()
                .extractingPath("$.totalElements")
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"passwordHash", "version,desc", "nonexistent"})
    void sortingIsRestrictedToAnAllowList(String sort) {
        MvcTestResult result =
                mvc.get().uri("/api/admin/users").param("sort", sort).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_SORT_PROPERTY");
    }

    @Test
    void updateReturnsTheNewVersionAndRejectsTheOldOne() {
        User user = users.save(new User("alice@example.com", "Alice", "h", Role.AGENT));
        String path = "/api/admin/users/" + user.getId();

        MvcTestResult first = patch(path, """
                {"displayName": "Alice Smith", "version": 0}
                """);
        assertThat(first).hasStatusOk();
        assertThat(first).bodyJson().extractingPath("$.displayName").isEqualTo("Alice Smith");
        assertThat(first).bodyJson().extractingPath("$.version").isEqualTo(1);

        MvcTestResult stale = patch(path, """
                {"displayName": "Overwritten", "version": 0}
                """);
        assertThat(stale).hasStatus(HttpStatus.CONFLICT);
        assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(users.findById(user.getId()).orElseThrow().getDisplayName()).isEqualTo("Alice Smith");
    }

    @Test
    void lastActiveAdminCannotBeDemoted() {
        User admin = users.save(new User("admin@example.com", "Admin", "h", Role.ADMIN));

        MvcTestResult result = patch("/api/admin/users/" + admin.getId(), """
                {"role": "AGENT", "version": 0}
                """);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("LAST_ADMIN_REQUIRED");
    }

    @Test
    void unknownUserIs404() {
        assertThat(mvc.get().uri("/api/admin/users/0199a000-0000-7000-8000-000000000000"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedIdIs400NotServerError() {
        assertThat(mvc.get().uri("/api/admin/users/not-a-uuid")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM_LEAD", "AGENT", "REQUESTER"})
    void nonAdminsAreForbidden(String role) {
        MvcTestResult result = mvc.get()
                .uri("/api/admin/users")
                .with(user("someone").roles(role))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    private MvcTestResult createUser(String json) {
        return mvc.post()
                .uri("/api/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult patch(String path, String json) {
        return mvc.patch()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static String agentJson(String email) {
        return """
                {"email": "%s", "displayName": "Grace", "role": "AGENT", "initialPassword": "a-sufficiently-long-pw"}
                """.formatted(email);
    }

    private String idOf(MvcTestResult result) {
        return users.findByEmail("grace.hopper@example.com")
                .orElseThrow()
                .getId()
                .toString();
    }
}
