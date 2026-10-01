package com.ademola.esm.ticket.workflow;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;

/** The requested status change is not part of the lifecycle (e.g. CLOSED -> IN_PROGRESS). */
public class InvalidTransitionException extends DomainException {

    InvalidTransitionException(String message) {
        super(ErrorCode.INVALID_STATUS_TRANSITION, message);
    }
}
