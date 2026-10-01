package com.ademola.esm.auth;

import static com.ademola.esm.support.AuthHelper.loginOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.support.AuthHelper.Credentials;
import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.MutableClock;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Attacks on the access token itself, plus role-based access using real tokens. */
@IntegrationTest
class JwtSecurityIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    MutableClock clock;

    User admin;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.reset();
        admin = testUsers.create("admin@example.com", Role.ADMIN);
    }

    @Test
    void validTokenIsAccepted() {
        assertThat(get("/api/users/me", loginOk(mvc, "admin@example.com").bearer()))
                .hasStatusOk();
    }

    @Test
    void expiredAccessTokenIsRejected() {
        Credentials credentials = loginOk(mvc, "admin@example.com");
        clock.advance(Duration.ofMinutes(16)); // 15 min lifetime + 30 s allowed clock skew

        assertUnauthorized(get("/api/users/me", credentials.bearer()));
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = loginOk(mvc, "admin@example.com").accessToken().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.replace("\"ADMIN\"", "\"ADMIN\" ").getBytes(StandardCharsets.UTF_8));

        assertUnauthorized(get("/api/users/me", "Bearer " + parts[0] + "." + forged + "." + parts[2]));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        byte[] attackerKey = "an-attacker-controlled-signing-key-32b!".getBytes(StandardCharsets.UTF_8);
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(attackerKey, "HmacSHA256")));
        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), adminClaims()))
                .getTokenValue();

        assertUnauthorized(get("/api/admin/users", "Bearer " + token));
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url(
                """
                {"iss":"esm","sub":"%s","role":"ADMIN","name":"x","exp":%d}
                """.formatted(admin.getId(), Instant.now().plusSeconds(600).getEpochSecond()));

        assertUnauthorized(get("/api/admin/users", "Bearer " + header + "." + payload + "."));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        // Correct key and algorithm, wrong issuer.
        byte[] ourKey = Base64.getDecoder().decode("dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kdWN0aW9uIQ==");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(ourKey, "HmacSHA256")));
        JwtClaimsSet claims =
                JwtClaimsSet.from(adminClaims()).issuer("someone-else").build();
        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertUnauthorized(get("/api/users/me", "Bearer " + token));
    }

    @Test
    void malformedAuthorizationHeaderIsRejected() {
        assertUnauthorized(get("/api/users/me", "Bearer not.a.jwt"));
    }

    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource({
        "REQUESTER, /api/admin/users, 403",
        "AGENT,     /api/admin/users, 403",
        "TEAM_LEAD, /api/admin/users, 403",
        "ADMIN,     /api/admin/users, 200",
        "REQUESTER, /api/teams,       403",
        "AGENT,     /api/teams,       200",
        "REQUESTER, /api/users/me,    200"
    })
    void roleBasedAccessWithRealTokens(Role role, String path, int expectedStatus) {
        String email = role.name().toLowerCase() + "-user@example.com";
        if (role != Role.ADMIN) {
            testUsers.create(email, role);
        } else {
            email = "admin@example.com";
        }

        assertThat(get(path, loginOk(mvc, email).bearer())).hasStatus(expectedStatus);
    }

    private JwtClaimsSet adminClaims() {
        return JwtClaimsSet.builder()
                .issuer("esm")
                .subject(admin.getId().toString())
                .claim("role", "ADMIN")
                .claim("name", "Mallory")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    private MvcTestResult get(String path, String authorization) {
        return mvc.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange();
    }

    private static void assertUnauthorized(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.strip().getBytes(StandardCharsets.UTF_8));
    }
}
