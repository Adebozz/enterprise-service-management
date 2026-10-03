package com.ademola.esm.ticket.comment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * The thread with author names, in one query. The visibility filter is part of the SQL: rows a
     * caller may not see are never loaded, so no later mapping step can leak them.
     */
    @Query("""
            select new com.ademola.esm.ticket.comment.CommentResponse(
                c.id, c.authorId, u.displayName, c.visibility, c.body, c.relatedStatus, c.createdAt)
            from Comment c join User u on u.id = c.authorId
            where c.workItemId = :workItemId and c.visibility in :visibilities
            order by c.createdAt, c.id
            """)
    List<CommentResponse> findThread(UUID workItemId, Collection<CommentVisibility> visibilities);
}
