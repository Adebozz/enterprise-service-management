package com.ademola.esm.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** One message to one person about one ticket, shown in the app and (optionally) emailed. */
@Entity
@Table(name = "notifications")
class Notification {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private NotificationKind kind;

    @Column(nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", nullable = false, length = 10)
    private EmailStatus emailStatus;

    @Column(name = "email_attempts", nullable = false)
    private short emailAttempts;

    @Column(name = "emailed_at")
    private Instant emailedAt;

    protected Notification() {}

    Notification(NotificationDraft draft, UUID workItemId, UUID actorId, Instant createdAt, boolean email) {
        this.recipientId = draft.recipientId();
        this.workItemId = workItemId;
        this.actorId = actorId;
        this.kind = draft.kind();
        this.message = draft.message().length() > 500 ? draft.message().substring(0, 499) + "…" : draft.message();
        this.createdAt = createdAt;
        this.emailStatus = email ? EmailStatus.PENDING : EmailStatus.SKIPPED;
    }

    void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    void emailSent(Instant now) {
        emailAttempts++;
        emailStatus = EmailStatus.SENT;
        emailedAt = now;
    }

    void emailFailed(int maxAttempts) {
        emailAttempts++;
        if (emailAttempts >= maxAttempts) {
            emailStatus = EmailStatus.FAILED;
        }
    }

    void emailSkipped() {
        emailStatus = EmailStatus.SKIPPED;
    }

    UUID getId() {
        return id;
    }

    UUID getRecipientId() {
        return recipientId;
    }

    UUID getWorkItemId() {
        return workItemId;
    }

    NotificationKind getKind() {
        return kind;
    }

    String getMessage() {
        return message;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getReadAt() {
        return readAt;
    }

    EmailStatus getEmailStatus() {
        return emailStatus;
    }

    int getEmailAttempts() {
        return emailAttempts;
    }
}
