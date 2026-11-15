package com.ademola.esm.ticket.attachment;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.comment.CommentVisibility;
import com.ademola.esm.ticket.query.NamedRef;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AttachmentService {

    /** A file and its name, served by the download endpoint. */
    public record Download(String fileName, String contentType, byte[] content) {}

    private final AttachmentRepository attachments;
    private final AttachmentContentStore contents;
    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final AuditService audit;
    private final Clock clock;

    public AttachmentService(
            AttachmentRepository attachments,
            AttachmentContentStore contents,
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            AuditService audit,
            Clock clock) {
        this.attachments = attachments;
        this.contents = contents;
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(CurrentUser user, UUID ticketId) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teams);
        return attachments.findForTicket(item.getId(), AttachmentPolicy.readableBy(user, item, teams)).stream()
                .map(row -> toResponse(user, item, row.attachment(), row.uploaderName()))
                .toList();
    }

    @PreAuthorize("isAuthenticated()")
    public AttachmentResponse add(
            CurrentUser user, UUID ticketId, CommentVisibility visibility, String originalName, byte[] content) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teams);
        if (!AttachmentPolicy.canAdd(user, item, teams, visibility)) {
            throw new BusinessRuleException(
                    ErrorCode.COMMENT_NOT_PERMITTED, "Only support staff handling this ticket can add internal files");
        }
        requireOpen(item, "accepts files");
        if (attachments.countByWorkItemId(item.getId()) >= AttachmentPolicy.MAX_PER_TICKET) {
            throw new BusinessRuleException(
                    ErrorCode.ATTACHMENT_LIMIT_REACHED,
                    "A ticket can have up to %d files; remove one first".formatted(AttachmentPolicy.MAX_PER_TICKET));
        }
        UploadedFile file = UploadedFile.accept(originalName, content);
        Attachment attachment =
                attachments.saveAndFlush(new Attachment(item.getId(), user.id(), visibility, file, clock.instant()));
        contents.put(attachment.getId(), file.content());
        audit.record(AuditRecord.event(
                AuditAction.ATTACHMENT_ADDED,
                AuditEntityType.WORK_ITEM,
                item.getId(),
                Map.of(
                        "attachmentId", attachment.getId(),
                        "fileName", attachment.getFileName(),
                        "sizeBytes", attachment.getSizeBytes(),
                        "visibility", visibility)));
        return toResponse(user, item, attachment, user.displayName());
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Download download(CurrentUser user, UUID ticketId, UUID attachmentId) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teams);
        Attachment attachment = visibleAttachment(user, item, teams, attachmentId);
        byte[] content = contents.get(attachment.getId())
                .orElseThrow(() -> new IllegalStateException("No content stored for attachment " + attachmentId));
        return new Download(attachment.getFileName(), attachment.getContentType(), content);
    }

    @PreAuthorize("isAuthenticated()")
    public void delete(CurrentUser user, UUID ticketId, UUID attachmentId) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teams);
        Attachment attachment = visibleAttachment(user, item, teams, attachmentId);
        requireOpen(item, "can't have files removed");
        if (!AttachmentPolicy.canDelete(user, item, attachment)) {
            throw new BusinessRuleException(
                    ErrorCode.ATTACHMENT_DELETE_NOT_PERMITTED, "Only whoever added a file, or an admin, can remove it");
        }
        contents.delete(attachment.getId());
        attachments.delete(attachment);
        audit.record(AuditRecord.event(
                AuditAction.ATTACHMENT_REMOVED,
                AuditEntityType.WORK_ITEM,
                item.getId(),
                Map.of("attachmentId", attachment.getId(), "fileName", attachment.getFileName())));
    }

    private AttachmentResponse toResponse(CurrentUser user, WorkItem item, Attachment attachment, String uploaderName) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getVisibility(),
                new NamedRef(attachment.getUploadedBy(), uploaderName),
                attachment.getCreatedAt(),
                AttachmentPolicy.canDelete(user, item, attachment));
    }

    private static void requireOpen(WorkItem item, String what) {
        if (item.isTerminal()) {
            throw new BusinessRuleException(
                    ErrorCode.TICKET_CLOSED,
                    "%s is %s; it no longer %s"
                            .formatted(item.getReference(), item.getStatusName().toLowerCase(), what));
        }
    }

    /** An internal file is "not found" for someone who may not see it, like an unknown id. */
    private Attachment visibleAttachment(CurrentUser user, WorkItem item, Set<UUID> teams, UUID attachmentId) {
        return attachments
                .findById(attachmentId)
                .filter(found -> found.getWorkItemId().equals(item.getId()))
                .filter(found -> AttachmentPolicy.readableBy(user, item, teams).contains(found.getVisibility()))
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
    }

    private WorkItem visibleTicket(CurrentUser user, UUID ticketId, Set<UUID> teams) {
        return workItems
                .findById(ticketId)
                .filter(item -> TicketAccessPolicy.canView(user, item, teams))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
    }
}
