package com.ademola.esm.notification;

/** Where a notification is in the email outbox. */
enum EmailStatus {
    /** Waiting for the email job. */
    PENDING,
    SENT,
    /** Gave up after {@link NotificationEmailService#MAX_ATTEMPTS} tries. */
    FAILED,
    /** Email was switched off when it was created, or the recipient can't receive it. */
    SKIPPED
}
