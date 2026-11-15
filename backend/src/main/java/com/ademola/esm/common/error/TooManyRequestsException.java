package com.ademola.esm.common.error;

import java.time.Duration;

/** The caller must wait before trying again; the response says how long ({@code Retry-After}). */
public class TooManyRequestsException extends DomainException {

    private final Duration retryAfter;

    public TooManyRequestsException(ErrorCode code, String message, Duration retryAfter) {
        super(code, message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
