package com.ademola.esm.catalogue;

/** The kinds of question a catalogue form can ask. Each has its own answer rule in {@link CatalogueForm}. */
public enum FieldType {
    /** One line, up to 500 characters. */
    TEXT,
    /** Several lines, up to 4,000 characters. */
    LONG_TEXT,
    /** One of the field's options. */
    SELECT,
    /** A calendar date, ISO format (2026-12-01). */
    DATE
}
