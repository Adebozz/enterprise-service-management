package com.ademola.esm.common.error;

import java.util.List;

/**
 * A business rule broken by particular fields of the request, reported the same way as bean
 * validation errors ({@code fieldErrors}), so a form can show each message next to its field. Used
 * where the rules come from data rather than annotations, such as a catalogue item's own form.
 */
public class InvalidFieldsException extends BusinessRuleException {

    private final List<GlobalExceptionHandler.FieldErrorDetail> fieldErrors;

    public InvalidFieldsException(
            ErrorCode code, String message, List<GlobalExceptionHandler.FieldErrorDetail> fieldErrors) {
        super(code, message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<GlobalExceptionHandler.FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }
}
