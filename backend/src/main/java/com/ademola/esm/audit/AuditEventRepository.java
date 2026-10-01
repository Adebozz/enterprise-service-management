package com.ademola.esm.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Deliberately extends the bare {@link Repository} rather than {@code JpaRepository}: the only
 * operations that exist are "append" and "read". There's no {@code delete} or {@code saveAll} to
 * misuse.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(AuditEntityType entityType, UUID entityId);
}
