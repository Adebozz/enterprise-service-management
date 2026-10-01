package com.ademola.esm.auth;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;

/**
 * The refresh token is missing, unknown, expired, revoked or reused. The client gets one generic
 * message: telling an attacker <em>why</em> a stolen token failed would only help them.
 */
class InvalidRefreshTokenException extends DomainException {

    InvalidRefreshTokenException() {
        super(ErrorCode.REFRESH_TOKEN_INVALID, "Session expired or invalid. Please sign in again.");
    }
}
