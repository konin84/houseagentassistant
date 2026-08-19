package com.digitalpartner.houseagent.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Wrapper every published event travels in.
 *
 * <p>Self-describing on purpose. Putting the type and agency in the message body
 * rather than only in Kafka headers means a consumer can route and establish its
 * tenant from the payload alone - which keeps the contract testable with an in-memory
 * connector, and survives replay from a log or a dead-letter table where headers may
 * not have been kept.
 *
 * @param eventId    idempotency key. Delivery is at least once, so consumers will see
 *                   the same id twice and must treat the second one as a no-op.
 * @param eventType  simple name of the payload record, e.g. {@code LeaseSigned}
 * @param agencyId   the agency this event belongs to; a consumer must establish it
 *                   before touching tenant-scoped data
 * @param occurredAt when the change happened, not when it was published
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String agencyId,
        Instant occurredAt,
        T payload) {

    public static <T> EventEnvelope<T> of(String eventType, String agencyId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, agencyId, Instant.now(), payload);
    }
}
