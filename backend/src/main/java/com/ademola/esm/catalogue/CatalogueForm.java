package com.ademola.esm.catalogue;

import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.GlobalExceptionHandler.FieldErrorDetail;
import com.ademola.esm.common.error.InvalidFieldsException;
import com.ademola.esm.ticket.request.RequestAnswer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a catalogue form: what a valid form looks like (for admins) and what valid answers
 * to it look like (for requesters). Pure functions, no I/O, so every rule is unit-tested directly.
 */
final class CatalogueForm {

    static final int MAX_FIELDS = 20;
    static final int MAX_TEXT = 500;
    static final int MAX_LONG_TEXT = 4_000;

    private CatalogueForm() {}

    /** Checks the rules a single field's annotations can't express. Errors point at {@code fields[i]}. */
    static void checkDefinition(List<FormField> fields) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (fields.size() > MAX_FIELDS) {
            errors.add(new FieldErrorDetail("fields", "A form can have at most " + MAX_FIELDS + " questions"));
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FormField field = fields.get(i);
            String path = "fields[" + i + "]";
            if (!keys.add(field.key())) {
                errors.add(new FieldErrorDetail(path + ".key", "Another question already uses this key"));
            }
            if (field.type() == FieldType.SELECT) {
                if (field.options().size() < 2) {
                    errors.add(new FieldErrorDetail(path + ".options", "A choice needs at least two options"));
                }
                Set<String> seen = new HashSet<>();
                if (!field.options().stream().map(String::trim).allMatch(seen::add)) {
                    errors.add(new FieldErrorDetail(path + ".options", "Options must be different from each other"));
                }
            } else if (!field.options().isEmpty()) {
                errors.add(new FieldErrorDetail(path + ".options", "Only a choice question has options"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(ErrorCode.INVALID_CATALOGUE_FORM, "The form is not valid", errors);
        }
    }

    /**
     * Validates a requester's answers and returns them in form order, trimmed, with each question's
     * label copied in. Unanswered optional questions are left out. Errors point at {@code answers.<key>}.
     */
    static List<RequestAnswer> answer(List<FormField> fields, Map<String, String> answers) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<String> known = new HashSet<>();
        List<RequestAnswer> accepted = new ArrayList<>();
        for (FormField field : fields) {
            known.add(field.key());
            String raw = answers.get(field.key());
            String value = raw == null ? "" : raw.trim();
            String path = "answers." + field.key();
            if (value.isEmpty()) {
                if (field.required()) {
                    errors.add(new FieldErrorDetail(path, "This question needs an answer"));
                }
                continue;
            }
            String problem = problemWith(field, value);
            if (problem != null) {
                errors.add(new FieldErrorDetail(path, problem));
            } else {
                accepted.add(new RequestAnswer(field.key(), field.label(), value));
            }
        }
        answers.keySet().stream()
                .filter(key -> !known.contains(key))
                .sorted()
                .forEach(key -> errors.add(new FieldErrorDetail("answers." + key, "This form has no such question")));
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(
                    ErrorCode.CATALOGUE_ANSWERS_INVALID, "Some answers need attention", errors);
        }
        return List.copyOf(accepted);
    }

    /**
     * The ticket description: every answer as "Question: answer" ("Which model? Developer" when the
     * question already ends in punctuation), then the requester's own notes.
     * Answers are also stored separately; this makes them readable everywhere a description is
     * shown, and searchable, without each of those places knowing about forms.
     */
    static String describe(List<RequestAnswer> answers, String notes) {
        StringBuilder text = new StringBuilder();
        for (RequestAnswer answer : answers) {
            String label = answer.label();
            boolean punctuated = label.endsWith("?") || label.endsWith(":");
            text.append(label)
                    .append(punctuated ? " " : ": ")
                    .append(answer.value())
                    .append('\n');
        }
        if (notes != null && !notes.isBlank()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(notes.trim());
        }
        return text.isEmpty()
                ? "Ordered from the service catalogue."
                : text.toString().strip();
    }

    private static String problemWith(FormField field, String value) {
        return switch (field.type()) {
            case TEXT -> value.length() > MAX_TEXT ? "Keep this under " + MAX_TEXT + " characters" : null;
            case LONG_TEXT ->
                value.length() > MAX_LONG_TEXT ? "Keep this under " + MAX_LONG_TEXT + " characters" : null;
            case SELECT -> field.options().contains(value) ? null : "Choose one of the options";
            case DATE -> isIsoDate(value) ? null : "Enter a date such as 2026-12-01";
        };
    }

    private static boolean isIsoDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
