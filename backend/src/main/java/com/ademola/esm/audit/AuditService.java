package com.ademola.esm.audit;

import com.ademola.esm.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit entries.
 *
 * <p>{@code Propagation.MANDATORY}: an audit entry can only be written inside the business
 * transaction that makes the change, and calling it without one throws. The change and its audit
 * record therefore commit or roll back together: no audited change that didn't happen, and no
 * change without its audit record.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final ActorProvider actorProvider;
    private final JsonMapper json;
    private final Clock clock;

    public AuditService(AuditEventRepository events, ActorProvider actorProvider, JsonMapper json, Clock clock) {
        this.events = events;
        this.actorProvider = actorProvider;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditRecord record) {
        events.save(new AuditEvent(
                clock.instant(),
                actorProvider.currentActorId().orElse(null),
                record.action(),
                record.entityType(),
                record.entityId(),
                toJson(record.oldValue()),
                toJson(record.newValue()),
                toJson(record.metadata()),
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private String toJson(Map<String, ?> value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
