package com.ademola.esm.ticket.attachment;

import com.ademola.esm.ticket.comment.CommentVisibility;
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

/**
 * A file added to a ticket. Only its description lives here; the bytes are kept by an
 * {@link AttachmentContentStore}. Immutable: a corrected file is a new attachment.
 */
@Entity
@Immutable
@Table(name = "attachments")
class Attachment {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommentVisibility visibility;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Attachment() {}

    Attachment(UUID workItemId, UUID uploadedBy, CommentVisibility visibility, UploadedFile file, Instant createdAt) {
        this.workItemId = workItemId;
        this.uploadedBy = uploadedBy;
        this.visibility = visibility;
        this.fileName = file.fileName();
        this.contentType = file.contentType();
        this.sizeBytes = file.content().length;
        this.sha256 = file.sha256();
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getWorkItemId() {
        return workItemId;
    }

    UUID getUploadedBy() {
        return uploadedBy;
    }

    CommentVisibility getVisibility() {
        return visibility;
    }

    String getFileName() {
        return fileName;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
