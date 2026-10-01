package com.ademola.esm.auth;

import static com.ademola.esm.support.AuthHelper.credentials;
import static com.ademola.esm.support.AuthHelper.login;
import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.AuthHelper;
import com.ademola.esm.support.AuthHelper.Credentials;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.MutableClock;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** End-to-end authentication flows through the real HTTP endpoints, tokens and database. */
@IntegrationTest
class AuthApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    UserRepository users;

    @Autowired
    MutableClock clock;

    User agent;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        agent = testUsers.create("agent@example.com", Role.AGENT);
    }

    // ----- login --------------------------------------------------------------------------------

    @Test
    void loginReturnsAccessTokenAndSetsHardenedRefreshCookie() {
        MvcTestResult result = login(mvc, "Agent@Example.com", TestUsers.PASSWORD);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
        assertThat(result).bodyJson().extractingPath("$.expiresIn").isEqualTo(900);
        assertThat(result).bodyJson().extractingPath("$.user.role").isEqualTo("AGENT");
        assertThat(result).bodyJson().doesNotHavePath("$.refreshToken"); // never exposed to JavaScript
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("esm_refresh=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=604800");
    }

    @Test
    void accessTokenAuthenticatesApiCallsAndMeReturnsTheCallersOwnProfile() {
        Credentials credentials = loginOk(mvc, "agent@example.com");

        MvcTestResult me = mvc.get()
                .uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, credentials.bearer())
                .exchange();

        assertThat(me).hasStatusOk();
        assertThat(me).bodyJson().extractingPath("$.id").isEqualTo(agent.getId().toString());
        assertThat(me).bodyJson().extractingPath("$.email").isEqualTo("agent@example.com");
        assertThat(me).bodyJson().extractingPath("$.teams").asArray().isEmpty();
    }

    @Test
    void unknownEmailWrongPasswordAndInactiveAccountAreIndistinguishable() {
        User inactive = testUsers.create("inactive@example.com", Role.AGENT);
        ReflectionTestUtils.setField(inactive, "active", false);
        users.save(inactive);

        MvcTestResult unknown = login(mvc, "nobody@example.com", TestUsers.PASSWORD);
        MvcTestResult wrongPassword = login(mvc, "agent@example.com", "wrong-password-123");
        MvcTestResult disabled = login(mvc, "inactive@example.com", TestUsers.PASSWORD);

        for (MvcTestResult result : List.of(unknown, wrongPassword, disabled)) {
            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
            assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Invalid email or password");
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
    }

    // ----- refresh ------------------------------------------------------------------------------

    @Test
    void refreshRotatesTheCookieAndIssuesANewAccessToken() {
        Credentials first = loginOk(mvc, "agent@example.com");

        MvcTestResult refreshed = refresh(first.refreshCookie());

        assertThat(refreshed).hasStatusOk();
        Credentials second = credentials(refreshed);
        assertThat(second.refreshCookie().getValue())
                .isNotEqualTo(first.refreshCookie().getValue());
        assertThat(mvc.get().uri("/api/users/me").header(HttpHeaders.AUTHORIZATION, second.bearer()))
                .hasStatusOk();
    }

    @Test
    void reusingARotatedTokenAfterTheGraceWindowRevokesTheWholeFamily() {
        Credentials stolen = loginOk(mvc, "agent@example.com");
        Credentials legitimate = credentials(refresh(stolen.refreshCookie())); // stolen token now "used"

        clock.advance(Duration.ofMinutes(5)); // well past the 30s grace window
        MvcTestResult replay = refresh(stolen.refreshCookie());

        assertThat(replay).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(replay).bodyJson().extractingPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");
        // The revocation committed even though the request failed, so the legitimate holder's
        // newer token is dead too. Both parties must sign in again; the thief is locked out.
        assertThat(refresh(legitimate.refreshCookie())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject(
                        "select count(*) from refresh_tokens where revocation_reason = 'REUSE_DETECTED'",
                        Integer.class))
                .isEqualTo(2);
    }

    @Test
    void reuseInsideTheGraceWindowIsRejectedButDoesNotRevoke() {
        Credentials original = loginOk(mvc, "agent@example.com");
        Credentials tabA = credentials(refresh(original.refreshCookie()));

        MvcTestResult tabB = refresh(original.refreshCookie()); // a second tab, a moment later

        assertThat(tabB).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(tabA.refreshCookie())).hasStatusOk(); // the session survives
    }

    @Test
    void sessionHasAnAbsoluteLifetimeThatRotationCannotExtend() {
        Credentials credentials = loginOk(mvc, "agent@example.com");
        clock.advance(Duration.ofDays(6));
        Credentials rotated = credentials(refresh(credentials.refreshCookie()));
        clock.advance(Duration.ofDays(1).plusMinutes(1)); // 7 days + 1 minute after login

        assertThat(refresh(rotated.refreshCookie())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshPicksUpRoleChangesAndRejectsDeactivatedUsers() {
        Credentials credentials = loginOk(mvc, "agent@example.com");
        jdbc.update("update users set role = 'TEAM_LEAD' where id = ?", agent.getId());

        MvcTestResult promoted = refresh(credentials.refreshCookie());
        assertThat(promoted).bodyJson().extractingPath("$.user.role").isEqualTo("TEAM_LEAD");

        jdbc.update("update users set active = false where id = ?", agent.getId());
        assertThat(refresh(credentials(promoted).refreshCookie())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithoutCookieOrWithGarbageIs401() {
        assertThat(mvc.post().uri("/api/auth/refresh")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(new Cookie(AuthHelper.REFRESH_COOKIE, "not-a-real-token")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void crossSiteOriginIsRejectedOnCookieAuthenticatedEndpoints() {
        Credentials credentials = loginOk(mvc, "agent@example.com");

        MvcTestResult result = mvc.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(credentials.refreshCookie())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_ORIGIN");
        // An allowed origin works.
        assertThat(mvc.post()
                        .uri("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .cookie(credentials.refreshCookie()))
                .hasStatusOk();
    }

    // ----- logout & password change -------------------------------------------------------------

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() {
        Credentials credentials = loginOk(mvc, "agent@example.com");

        MvcTestResult logout = mvc.post()
                .uri("/api/auth/logout")
                .cookie(credentials.refreshCookie())
                .exchange();

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("esm_refresh=", "Max-Age=0");
        assertThat(refresh(credentials.refreshCookie())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutIsIdempotentAndNeedsNoAccessToken() {
        assertThat(mvc.post().uri("/api/auth/logout")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void changingPasswordEndsEverySessionAndOnlyTheNewPasswordWorks() {
        Credentials laptop = loginOk(mvc, "agent@example.com");
        Credentials phone = loginOk(mvc, "agent@example.com");

        MvcTestResult change = mvc.post()
                .uri("/api/users/me/password")
                .header(HttpHeaders.AUTHORIZATION, laptop.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword": "%s", "newPassword": "a-brand-new-password"}
                        """.formatted(TestUsers.PASSWORD))
                .exchange();

        assertThat(change).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(refresh(laptop.refreshCookie())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(phone.refreshCookie())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(login(mvc, "agent@example.com", TestUsers.PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(login(mvc, "agent@example.com", "a-brand-new-password")).hasStatusOk();
    }

    @Test
    void changingPasswordWithWrongCurrentPasswordFails() {
        Credentials credentials = loginOk(mvc, "agent@example.com");

        MvcTestResult change = mvc.post()
                .uri("/api/users/me/password")
                .header(HttpHeaders.AUTHORIZATION, credentials.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword": "not-the-password", "newPassword": "a-brand-new-password"}
                        """)
                .exchange();

        assertThat(change).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(change).bodyJson().extractingPath("$.code").isEqualTo("CURRENT_PASSWORD_INCORRECT");
        assertThat(refresh(credentials.refreshCookie())).hasStatusOk(); // nothing was revoked
    }

    private MvcTestResult refresh(Cookie cookie) {
        return mvc.post().uri("/api/auth/refresh").cookie(cookie).exchange();
    }
}
