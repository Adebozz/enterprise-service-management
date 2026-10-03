package com.ademola.esm.ticket.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** A message on a ticket. Immutable once written; corrections are new comments. */
@Entity
@Immutable
@Table(name = "comments")
class Comment {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommentVisibility visibility;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "related_status", length = 30)
    private String relatedStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Comment() {}

    Comment(
            UUID workItemId,
            UUID authorId,
            CommentVisibility visibility,
            String body,
            String relatedStatus,
            Instant createdAt) {
        this.workItemId = workItemId;
        this.authorId = authorId;
        this.visibility = visibility;
        this.body = body.strip();
        this.relatedStatus = relatedStatus;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    CommentVisibility getVisibility() {
        return visibility;
    }

    String getBody() {
        return body;
    }

    String getRelatedStatus() {
        return relatedStatus;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
