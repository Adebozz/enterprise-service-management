package com.ademola.esm.audit;

import com.ademola.esm.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p>Each entry is also published as an {@link AuditRecordedEvent}, in the same transaction, so other
 * modules (notifications) can react to what happened without every feature calling them.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final ActorProvider actorProvider;
    private final JsonMapper json;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public AuditService(
            AuditEventRepository events,
            ActorProvider actorProvider,
            JsonMapper json,
            ApplicationEventPublisher publisher,
            Clock clock) {
        this.events = events;
        this.actorProvider = actorProvider;
        this.json = json;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditRecord record) {
        save(record, actorProvider.currentActorId().orElse(null));
    }

    /**
     * Records something the system did on its own (e.g. an SLA breach), with no actor even when it
     * was noticed during a user's request: the user didn't cause it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAsSystem(AuditRecord record) {
        save(record, null);
    }

    private void save(AuditRecord record, UUID actorId) {
        Instant now = clock.instant();
        events.save(new AuditEvent(
                now,
                actorId,
                record.action(),
                record.entityType(),
                record.entityId(),
                toJson(record.oldValue()),
                toJson(record.newValue()),
                toJson(record.metadata()),
                MDC.get(CorrelationIdFilter.MDC_KEY)));
        publisher.publishEvent(new AuditRecordedEvent(record, actorId, now));
    }

    private String toJson(Map<String, ?> value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
