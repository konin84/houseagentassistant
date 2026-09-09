package com.digitalpartner.houseagent.agency.outbox;

import com.digitalpartner.houseagent.common.events.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


/**
 * Records an event for later publication, inside the caller's transaction.
 *
 * <p>No {@code @Transactional} here on purpose: this must join the transaction that is
 * settling the payment, never start one of its own. An outbox row committed
 * independently of the payment it describes would reintroduce exactly the dual-write
 * problem the outbox exists to remove.
 */
@ApplicationScoped
public class OutboxWriter {

    @Inject
    ObjectMapper objectMapper;

    public void record(String aggregateType, String aggregateId, String agencyId,
                       String eventType, Object payload) {
        // The stored payload is the complete envelope, so the relay is a dumb pipe and
        // the message a consumer receives is byte-identical to what was committed.
        EventEnvelope<Object> envelope = EventEnvelope.of(eventType, agencyId, payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // Fail the whole transaction. A plan change whose event cannot be
            // serialised would leave property-service enforcing the old limit forever.
            throw new IllegalStateException("Cannot serialise " + eventType + " event", e);
        }
        OutboxEvent.pending(aggregateType, aggregateId, agencyId, eventType, json).persist();
    }
}
