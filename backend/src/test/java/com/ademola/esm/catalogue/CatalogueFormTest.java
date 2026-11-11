package com.ademola.esm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.GlobalExceptionHandler.FieldErrorDetail;
import com.ademola.esm.common.error.InvalidFieldsException;
import com.ademola.esm.ticket.request.RequestAnswer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CatalogueFormTest {

    static final FormField MODEL =
            new FormField("model", "Which model?", FieldType.SELECT, true, List.of("Standard", "Developer"), null);
    static final FormField NEEDED_BY = new FormField("neededBy", "Needed by", FieldType.DATE, false, null, null);
    static final FormField REASON = new FormField("reason", "Why?", FieldType.LONG_TEXT, true, null, "Be brief");
    static final FormField ASSET = new FormField("assetTag", "Old asset tag", FieldType.TEXT, false, null, null);
    static final List<FormField> FORM = List.of(MODEL, NEEDED_BY, REASON, ASSET);

    @Nested
    class Definition {

        @Test
        void aWellFormedFormPasses() {
            CatalogueForm.checkDefinition(FORM);
            CatalogueForm.checkDefinition(List.of()); // an item may ask nothing
        }

        @Test
        void keysMustBeUnique() {
            FormField duplicate = new FormField("model", "Again", FieldType.TEXT, false, null, null);

            assertThat(errorsOf(() -> CatalogueForm.checkDefinition(List.of(MODEL, duplicate))))
                    .containsExactly(new FieldErrorDetail("fields[1].key", "Another question already uses this key"));
        }

        @Test
        void aChoiceNeedsTwoDistinctOptions() {
            FormField one = new FormField("a", "A", FieldType.SELECT, true, List.of("Only"), null);
            FormField repeated = new FormField("b", "B", FieldType.SELECT, true, List.of("Yes", " Yes"), null);

            assertThat(errorsOf(() -> CatalogueForm.checkDefinition(List.of(one, repeated))))
                    .extracting(FieldErrorDetail::field)
                    .containsExactly("fields[0].options", "fields[1].options");
        }

        @Test
        void onlyAChoiceHasOptions() {
            FormField text = new FormField("a", "A", FieldType.TEXT, false, List.of("x", "y"), null);

            assertThat(errorsOf(() -> CatalogueForm.checkDefinition(List.of(text))))
                    .containsExactly(new FieldErrorDetail("fields[0].options", "Only a choice question has options"));
        }

        @Test
        void aFormHasAtMostTwentyQuestions() {
            List<FormField> many = IntStream.range(0, 21)
                    .mapToObj(i -> new FormField("q" + i, "Q" + i, FieldType.TEXT, false, null, null))
                    .toList();

            assertThat(errorsOf(() -> CatalogueForm.checkDefinition(many)))
                    .extracting(FieldErrorDetail::field)
                    .containsExactly("fields");
        }

        @Test
        void errorsCarryTheFormCode() {
            assertThatThrownBy(() -> CatalogueForm.checkDefinition(List.of(MODEL, MODEL)))
                    .isInstanceOf(InvalidFieldsException.class)
                    .extracting(e -> ((InvalidFieldsException) e).code())
                    .isEqualTo(ErrorCode.INVALID_CATALOGUE_FORM);
        }
    }

    @Nested
    class Answers {

        @Test
        void validAnswersComeBackInFormOrderTrimmedWithTheirLabels() {
            Map<String, String> answers =
                    Map.of("reason", "  Mine broke ", "model", "Developer", "neededBy", "2026-12-01");

            assertThat(CatalogueForm.answer(FORM, answers))
                    .containsExactly(
                            new RequestAnswer("model", "Which model?", "Developer"),
                            new RequestAnswer("neededBy", "Needed by", "2026-12-01"),
                            new RequestAnswer("reason", "Why?", "Mine broke"));
        }

        @Test
        void blankOptionalAnswersAreLeftOut() {
            Map<String, String> answers = new HashMap<>();
            answers.put("model", "Standard");
            answers.put("reason", "New starter");
            answers.put("assetTag", "   ");
            answers.put("neededBy", null);

            assertThat(CatalogueForm.answer(FORM, answers))
                    .extracting(RequestAnswer::key)
                    .containsExactly("model", "reason");
        }

        @Test
        void everyProblemIsReportedAgainstItsQuestion() {
            Map<String, String> answers = Map.of(
                    "model", "Gaming rig",
                    "neededBy", "next Tuesday",
                    "assetTag", "x".repeat(CatalogueForm.MAX_TEXT + 1),
                    "colour", "red");

            assertThat(errorsOf(() -> CatalogueForm.answer(FORM, answers)))
                    .containsExactly(
                            new FieldErrorDetail("answers.model", "Choose one of the options"),
                            new FieldErrorDetail("answers.neededBy", "Enter a date such as 2026-12-01"),
                            new FieldErrorDetail("answers.reason", "This question needs an answer"),
                            new FieldErrorDetail("answers.assetTag", "Keep this under 500 characters"),
                            new FieldErrorDetail("answers.colour", "This form has no such question"));
        }

        @Test
        void longTextAllowsMoreThanText() {
            Map<String, String> answers =
                    Map.of("model", "Standard", "reason", "y".repeat(CatalogueForm.MAX_LONG_TEXT));

            assertThat(CatalogueForm.answer(FORM, answers)).hasSize(2);
            assertThat(errorsOf(() -> CatalogueForm.answer(
                            FORM, Map.of("model", "Standard", "reason", "y".repeat(CatalogueForm.MAX_LONG_TEXT + 1)))))
                    .extracting(FieldErrorDetail::field)
                    .containsExactly("answers.reason");
        }

        @Test
        void impossibleDatesAreRejected() {
            assertThat(errorsOf(() -> CatalogueForm.answer(
                            FORM, Map.of("model", "Standard", "reason", "r", "neededBy", "2026-02-30"))))
                    .extracting(FieldErrorDetail::field)
                    .containsExactly("answers.neededBy");
        }
    }

    @Nested
    class Description {

        @Test
        void listsEachAnswerThenTheNotes() {
            List<RequestAnswer> answers = List.of(
                    new RequestAnswer("model", "Which model?", "Developer"),
                    new RequestAnswer("tag", "Asset tag", "LT-1"),
                    new RequestAnswer("room", "Room:", "4.02"));

            // A question that already ends in punctuation gets no extra colon.
            assertThat(CatalogueForm.describe(answers, "  Please deliver to desk 4 "))
                    .isEqualTo("Which model? Developer\nAsset tag: LT-1\nRoom: 4.02\n\nPlease deliver to desk 4");
        }

        @Test
        void isNeverBlank() {
            assertThat(CatalogueForm.describe(List.of(), "  ")).isEqualTo("Ordered from the service catalogue.");
            assertThat(CatalogueForm.describe(List.of(), "Just notes")).isEqualTo("Just notes");
        }
    }

    private static List<FieldErrorDetail> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            return e.fieldErrors();
        }
        throw new AssertionError("expected InvalidFieldsException");
    }
}
