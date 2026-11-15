package com.ademola.esm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.MutableClock;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Failed sign-ins are limited per account and per address, without revealing which accounts exist. */
@IntegrationTest
class LoginThrottleIT {

    static final String HOME = "203.0.113.10";
    static final String ELSEWHERE = "198.51.100.20";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        users.create("ada@example.com", Role.REQUESTER);
        users.create("bob@example.com", Role.REQUESTER);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void afterFiveFailuresEvenTheRightPasswordWaitsUntilTheWindowPasses() {
        for (int i = 0; i < 5; i++) {
            assertThat(login("ada@example.com", "wrong-password", HOME)).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        MvcTestResult throttled = login("ada@example.com", TestUsers.PASSWORD, HOME);
        assertThat(throttled).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled).bodyJson().extractingPath("$.code").isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(Long.parseLong(throttled.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(800L, 900L);
        assertThat(login("ada@example.com", TestUsers.PASSWORD, ELSEWHERE))
                .as("the account is protected wherever the guesses come from")
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login("bob@example.com", TestUsers.PASSWORD, HOME)).hasStatusOk();

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(login("ada@example.com", TestUsers.PASSWORD, HOME)).hasStatusOk();
    }

    @Test
    void signingInSuccessfullyClearsTheAccountsFailures() {
        for (int i = 0; i < 4; i++) {
            login("ada@example.com", "wrong-password", HOME);
        }
        assertThat(login("ada@example.com", TestUsers.PASSWORD, HOME)).hasStatusOk();
        for (int i = 0; i < 4; i++) {
            login("ada@example.com", "wrong-password", HOME);
        }

        assertThat(login("ada@example.com", TestUsers.PASSWORD, HOME)).hasStatusOk();
    }

    @Test
    void unknownEmailsAreThrottledExactlyLikeRealOnes() {
        for (int i = 0; i < 5; i++) {
            assertThat(login("ghost@example.com", "wrong-password", HOME)).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        assertThat(login("ghost@example.com", "wrong-password", HOME)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oneAddressTryingManyAccountsIsStoppedToo() {
        for (int i = 0; i < 20; i++) {
            login("user" + i + "@example.com", "Summer2026!", HOME);
        }

        assertThat(login("bob@example.com", TestUsers.PASSWORD, HOME)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login("bob@example.com", TestUsers.PASSWORD, ELSEWHERE)).hasStatusOk();
    }

    @Test
    void theTableKeepsNoEmailsAndNothingOlderThanTheWindow() {
        login("ada@example.com", "wrong-password", HOME);
        assertThat(jdbc.queryForObject("select account_hash from login_failures", String.class))
                .hasSize(64)
                .doesNotContain("ada");

        clock.advance(Duration.ofMinutes(16));
        login("bob@example.com", "wrong-password", HOME);
        assertThat(jdbc.queryForObject("select count(*) from login_failures", Integer.class))
                .isEqualTo(1);
    }

    private MvcTestResult login(String email, String password, String address) {
        return mvc.post()
                .uri("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password))
                .exchange();
    }
}
