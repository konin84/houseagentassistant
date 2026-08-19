package com.digitalpartner.houseagent.lease.outbox;

import com.digitalpartner.houseagent.common.events.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * Records an event for later publication, inside the caller's transaction.
 *
 * <p>There is no {@code @Transactional} here on purpose: this must join the
 * transaction that is changing the lease, never start one of its own. An outbox row
 * committed independently of the lease it describes would reintroduce exactly the
 * dual-write problem the outbox exists to remove.
 */
@ApplicationScoped
public class OutboxWriter {

    @Inject
    ObjectMapper objectMapper;

    public void record(String aggregateType, UUID aggregateId, String agencyId,
                       String eventType, Object payload) {
        // The stored payload is the complete envelope, so the relay is a dumb pipe and
        // the message a consumer receives is byte-identical to what was committed.
        EventEnvelope<Object> envelope = EventEnvelope.of(eventType, agencyId, payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // Fail the whole transaction. Committing a lease whose event can never be
            // serialised would leave the rest of the platform permanently out of step.
            throw new IllegalStateException("Cannot serialise " + eventType + " event", e);
        }
        OutboxEvent.pending(aggregateType, aggregateId, agencyId, eventType, json).persist();
    }
}
