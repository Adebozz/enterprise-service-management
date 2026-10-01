package com.ademola.esm.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of every error response.
 *
 * <p>Clients (including our React app) should branch on these codes, never on the human-readable
 * message. Domain-specific codes are added alongside the features that raise them.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    INVALID_SORT_PROPERTY(HttpStatus.BAD_REQUEST),

    // authentication
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    INVALID_ORIGIN(HttpStatus.FORBIDDEN),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST),

    // user / team
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),
    LAST_ADMIN_REQUIRED(HttpStatus.CONFLICT),
    TEAM_NAME_ALREADY_EXISTS(HttpStatus.CONFLICT),
    TEAM_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
    USER_NOT_ELIGIBLE_FOR_TEAM(HttpStatus.UNPROCESSABLE_CONTENT),

    REQUEST_FAILED(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Best-effort code for errors raised by Spring MVC itself (404 for unknown route, 405, 415...). */
    static ErrorCode forFrameworkStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : REQUEST_FAILED;
        };
    }
}
