package com.digitalpartner.houseagent.agency.outbox;

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
 * One agency event waiting to be published to Kafka.
 *
 * <p>A plan change committed but never published would leave an agency paying for a
 * tier that property-service still refuses to honour - and no amount of retrying fixes
 * an event that was never written. Publishing before the change committed would be the
 * mirror image: an agency allowed a hundred houses by a transaction that rolled back.
 *
 * <p>Writing the event into this table inside the same transaction removes the gap:
 * either both commit or neither does.
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

    /**
     * The Kafka partition key, so two changes to one agency stay in order - which for a
     * plan is the difference between an upgrade and a downgrade.
     *
     * <p>A string rather than a UUID: an agency is identified by a slug, chosen once and
     * readable in a token.
     */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    public String aggregateId;

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

    public static OutboxEvent pending(String aggregateType, String aggregateId,
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
