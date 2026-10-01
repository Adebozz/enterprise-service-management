package com.ademola.esm.common.error;

/**
 * A request that is well-formed but violates a business rule (duplicate name, last admin, ineligible
 * team member...). The {@link ErrorCode} carries the HTTP status and the machine-readable code, so
 * most rules don't need their own exception class.
 */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(ErrorCode code, String message) {
        super(code, message);
    }
}
