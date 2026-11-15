package com.ademola.esm.ticket.attachment;

import com.ademola.esm.ticket.comment.CommentVisibility;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** Uploader names in the same query; rows the caller may not see are never loaded. */
    @Query("""
            select new com.ademola.esm.ticket.attachment.AttachmentRow(a, u.displayName)
            from Attachment a join User u on u.id = a.uploadedBy
            where a.workItemId = :workItemId and a.visibility in :visibilities
            order by a.createdAt, a.id
            """)
    List<AttachmentRow> findForTicket(UUID workItemId, Collection<CommentVisibility> visibilities);

    long countByWorkItemId(UUID workItemId);
}
