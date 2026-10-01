package com.ademola.esm.common.error;

/**
 * Base class for expected business-rule failures (invalid transition, not found, conflict...).
 *
 * <p>The message of a {@code DomainException} is written for API clients and is returned in the
 * response. Never put internal details (SQL, stack traces, other users' data) in it.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode code;

    protected DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
