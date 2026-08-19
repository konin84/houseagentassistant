package com.digitalpartner.houseagent.payment.outbox;

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
 * One payment event waiting to be published to Kafka.
 *
 * <p>The dual-write problem is worse here than anywhere else on the platform. A
 * payment that settles but whose event is lost leaves the landlord permanently
 * un-notified about money that reached their account, and no amount of retrying the
 * email recovers an event that was never published. Conversely, publishing before the
 * database commits would tell a landlord they had been paid when the payment row had
 * rolled back.
 *
 * <p>Writing the event into this table inside the payment's own transaction removes
 * the gap: either both commit or neither does.
 *
 * <p>No {@code @TenantId}: the relay runs on a scheduler with no JWT and must see
 * every agency's pending events.
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

    /** Used as the Kafka partition key, so events about one payment stay ordered. */
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
