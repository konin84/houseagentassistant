package com.digitalpartner.houseagent.lease.outbox;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One event waiting to be published to Kafka.
 *
 * <h2>Why an outbox rather than publishing directly</h2>
 *
 * Writing a lease to PostgreSQL and publishing to Kafka are two systems, and there is
 * no transaction spanning both. Publish first and the database write may fail, leaving
 * property-service convinced a house is let when it is not. Publish after commit and a
 * crash in between loses the event, leaving a let house advertised forever. Neither
 * error corrects itself.
 *
 * <p>Writing the event into this table <em>inside</em> the same transaction as the
 * lease removes the gap: either both are committed or neither is. A relay then moves
 * committed rows to Kafka, retrying safely because {@link #publishedAt} makes the
 * hand-off idempotent.
 *
 * <p>No {@code @TenantId} here: the relay runs on a scheduler with no JWT and must see
 * every agency's pending events. The agency travels inside the row instead, and is
 * copied onto the Kafka message so the consumer can re-establish it.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    public String aggregateType;

    /** Used as the Kafka partition key, so events about one lease stay ordered. */
    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(name = "agency_id", nullable = false, length = 64)
    public String agencyId;

    @Column(name = "event_type", nullable = false, length = 100)
    public String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /** Null until the relay has handed the event to Kafka. */
    @Column(name = "published_at")
    public Instant publishedAt;

    public static OutboxEvent pending(String aggregateType, UUID aggregateId,
                                      String agencyId, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.agencyId = agencyId;
        event.eventType = eventType;
        event.payload = payload;
        return event;
    }
}
