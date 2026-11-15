package com.ademola.esm.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByRecipientIdOrderByCreatedAtDescIdDesc(UUID recipientId, Limit limit);

    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientId = :recipientId and n.readAt is null")
    int markAllRead(UUID recipientId, Instant now);

    /**
     * The next emails to send. {@code SKIP LOCKED}: if two backend instances run the job at once,
     * each takes different rows instead of both sending the same email.
     */
    @Query(value = """
                    select * from notifications where email_status = 'PENDING'
                    order by created_at, id limit :batchSize
                    for update skip locked
                    """, nativeQuery = true)
    List<Notification> lockPendingEmails(int batchSize);

    @Query("""
            select new com.ademola.esm.notification.NotificationRecipient(u.id, u.email, u.displayName, u.active)
            from User u where u.id in :ids
            """)
    List<NotificationRecipient> findRecipients(Collection<UUID> ids);
}
