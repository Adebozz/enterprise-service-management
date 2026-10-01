package com.ademola.esm.common.error;

import com.ademola.esm.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every exception into an RFC 9457 {@code application/problem+json} response.
 *
 * <p>Response shape: {@code type, title, status, detail, instance} (standard) plus our extensions
 * {@code code, timestamp, correlationId} and, for validation errors, {@code fieldErrors}.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own exceptions (unknown
 * route, wrong HTTP method, unreadable JSON...) get the same shape. Security exceptions are routed
 * here too (see {@code SecurityConfig}), so there is exactly one place that renders errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldErrorDetail(String field, String message) {}

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
        return respond(ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(
                ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required to access this resource", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return respond(
                ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by someone else. Reload it and try again.",
                request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldErrorDetail> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ResponseEntity<ProblemDetail> response =
                respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", request);
        response.getBody().setProperty("fieldErrors", errors);
        return response;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Full detail goes to the log (correlated via MDC); the client gets a generic message only.
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed", path(request));
        problem.setProperty("fieldErrors", errors);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    /**
     * Enriches the ProblemDetail that Spring MVC builds for its own exceptions with our extensions.
     *
     * <p>We must enrich the <em>result</em> of {@code super}: for several exceptions (405, unknown
     * route...) Spring passes {@code body == null} and only creates the ProblemDetail inside super.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            @Nullable HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ErrorCode code = ErrorCode.forFrameworkStatus(statusCode.value());
            if (code == ErrorCode.MALFORMED_REQUEST) {
                // The default detail can echo parser internals; keep the client message generic.
                problem.setDetail("The request body or parameters could not be read");
            } else if (code == ErrorCode.RESOURCE_NOT_FOUND) {
                // The default detail mentions "static resource", which is an implementation detail.
                problem.setDetail("No endpoint exists at this path");
            }
            addExtensions(problem, code, path(request));
        }
        return response;
    }

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(code.status()).body(problem(code, detail, request.getRequestURI()));
    }

    private static ProblemDetail problem(ErrorCode code, String detail, @Nullable String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(HttpStatus.valueOf(code.status().value()).getReasonPhrase());
        addExtensions(problem, code, path);
        return problem;
    }

    private static void addExtensions(ProblemDetail problem, ErrorCode code, @Nullable String path) {
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    private static @Nullable String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
