package com.ademola.esm.ticket.request;

/**
 * One answered question on a catalogue request, kept as the requester saw it: the label is a copy,
 * so renaming the question later doesn't rewrite old requests. {@code key} identifies the question
 * within its item's form.
 */
public record RequestAnswer(String key, String label, String value) {}
