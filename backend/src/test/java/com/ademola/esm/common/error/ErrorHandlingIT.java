package com.ademola.esm.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.common.web.CorrelationIdFilter;
import com.ademola.esm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Verifies the error response contract (RFC 9457 ProblemDetail + our extensions) end to end. */
@IntegrationTest
class ErrorHandlingIT {

    @Autowired
    MockMvcTester mvc;

    @Test
    void unauthenticatedRequestGets401ProblemWithCorrelationId() {
        MvcTestResult result = mvc.get()
                .uri("/api/anything")
                .header(CorrelationIdFilter.HEADER, "test-correlation-0001")
                .exchange();

        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(CorrelationIdFilter.HEADER, "test-correlation-0001");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/anything");
        assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo("test-correlation-0001");
        assertThat(result).bodyJson().hasPath("$.timestamp");
    }

    @Test
    @WithMockUser
    void beanValidationFailureListsEveryInvalidField() {
        MvcTestResult result = mvc.post()
                .uri("/test/errors/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": " ", "code": "TOO-LONG"}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.fieldErrors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("title", "code");
    }

    @Test
    @WithMockUser
    void malformedJsonGetsGenericMessageWithoutParserInternals() {
        MvcTestResult result = mvc.post()
                .uri("/test/errors/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": ")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The request body or parameters could not be read");
    }

    @Test
    @WithMockUser
    void domainExceptionMapsToItsErrorCodeAndStatus() {
        MvcTestResult result = mvc.get().uri("/test/errors/not-found").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Probe 42 was not found");
    }

    @Test
    @WithMockUser
    void unexpectedExceptionNeverLeaksInternalDetails() {
        MvcTestResult result = mvc.get().uri("/test/errors/unexpected").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred");
        assertThat(result)
                .bodyText()
                .doesNotContain("secret internal detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("trace");
    }

    @Test
    @WithMockUser
    void wrongHttpMethodIsReportedConsistently() {
        MvcTestResult result = mvc.delete().uri("/test/errors/not-found").exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    @WithMockUser
    void unknownRouteForAuthenticatedUserIs404Problem() {
        MvcTestResult result = mvc.get().uri("/api/does-not-exist").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }
}
