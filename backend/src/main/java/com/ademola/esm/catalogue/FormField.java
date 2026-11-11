package com.ademola.esm.catalogue;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One question on a catalogue item's form. {@code key} names the answer in a submission and
 * never shows to requesters; {@code label} is the question as they read it. Only SELECT fields
 * have options. Rules that span fields (unique keys, options only on SELECT) are in
 * {@link CatalogueForm#checkDefinition}.
 */
public record FormField(
        @NotBlank @Pattern(regexp = "[a-z][a-zA-Z0-9]{0,39}", message = "must be camelCase letters and digits")
        String key,

        @NotBlank @Size(max = 100) String label,
        @NotNull FieldType type,
        boolean required,
        @Nullable @Size(max = 30) List<@NotBlank @Size(max = 100) String> options,
        @Nullable @Size(max = 300) String helpText) {

    public FormField {
        options = options == null ? List.of() : List.copyOf(options);
        helpText = helpText == null || helpText.isBlank() ? null : helpText.trim();
    }
}
