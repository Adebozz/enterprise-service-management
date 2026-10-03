package com.ademola.esm.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the audit trail. Applies no authorization of its own: callers (e.g. ticket history)
 * decide who may see which entity's timeline.
 */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditEventRepository events;
    private final JsonMapper json;

    public AuditQueryService(AuditEventRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    public List<AuditEntry> timeline(AuditEntityType entityType, UUID entityId) {
        return events.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(entityType, entityId).stream()
                .map(event -> new AuditEntry(
                        event.getId(),
                        event.getOccurredAt(),
                        event.getActorId(),
                        event.getAction(),
                        parse(event.getOldValue()),
                        parse(event.getNewValue()),
                        parse(event.getMetadata())))
                .toList();
    }

    private JsonNode parse(String value) {
        return value == null ? null : json.readTree(value);
    }
}
