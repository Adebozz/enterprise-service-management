package com.ademola.esm.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.io.UnsupportedEncodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Logs in through the real /api/auth/login endpoint and exposes the resulting credentials. */
public final class AuthHelper {

    public static final String REFRESH_COOKIE = "esm_refresh";

    private AuthHelper() {}

    public record Credentials(String accessToken, Cookie refreshCookie) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    public static MvcTestResult login(MockMvcTester mvc, String email, String password) {
        return mvc.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password))
                .exchange();
    }

    public static Credentials loginOk(MockMvcTester mvc, String email) {
        MvcTestResult result = login(mvc, email, TestUsers.PASSWORD);
        assertThat(result).hasStatusOk();
        return credentials(result);
    }

    /** Extracts the access token (body) and refresh cookie (Set-Cookie) from a login/refresh response. */
    public static Credentials credentials(MvcTestResult result) {
        try {
            String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
            Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
            assertThat(cookie)
                    .as("refresh cookie in %s", result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .isNotNull();
            return new Credentials(token, new Cookie(REFRESH_COOKIE, cookie.getValue()));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
