package com.ademola.esm.ticket.workflow;

/** Optional data accompanying a transition; which fields are mandatory depends on the transition. */
public record TransitionInput(String reason, String resolutionCode, String notes) {

    public static final TransitionInput NONE = new TransitionInput(null, null, null);

    boolean hasReason() {
        return isPresent(reason);
    }

    boolean hasResolutionCode() {
        return isPresent(resolutionCode);
    }

    boolean hasNotes() {
        return isPresent(notes);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
