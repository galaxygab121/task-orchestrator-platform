package com.taskorchestrator.core.outbox;

import com.taskorchestrator.core.messaging.EventCodec;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends an outbox row. MANDATORY propagation: it must share the caller's state-change transaction. */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final EventCodec codec;

    public OutboxWriter(OutboxRepository repository, EventCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(UUID eventId, UUID aggregateId, String topic, String eventType, Object event,
            Instant createdAt, Instant availableAt) {
        return repository.save(new OutboxEvent(eventId, aggregateId, topic, eventType, codec.encode(event), createdAt, availableAt));
    }
}
