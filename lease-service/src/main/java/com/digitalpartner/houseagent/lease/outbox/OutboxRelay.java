package com.digitalpartner.houseagent.lease.outbox;

import com.digitalpartner.houseagent.common.events.Topics;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Moves committed outbox rows to Kafka.
 *
 * <p>Runs on a timer rather than reacting to writes, which keeps it off the request
 * path: a broker outage slows publication but never fails a lease signing.
 *
 * <p>Delivery is <em>at least once</em>. If the broker accepts a message and this
 * process dies before marking the row published, the event is sent again after
 * restart. That is the correct trade - marking published before sending would lose
 * events outright. Consumers must therefore be idempotent, which is why
 * property-service keys its projection on the house id rather than appending to it.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    /** Bounded so one poll cannot pull an unbounded backlog into memory. */
    private static final int BATCH_SIZE = 100;

    @Inject
    @Channel("lease-events")
    Emitter<String> emitter;

    @Scheduled(
            every = "${app.outbox.poll-interval:2s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledRelay() {
        relayPending();
    }

    /**
     * Publishes every pending event, returning how many were sent.
     *
     * <p>Package-visible and separate from the schedule so tests can drive it
     * explicitly rather than waiting on a timer.
     */
    public int relayPending() {
        List<OutboxEvent> pending = fetchPending();
        int sent = 0;
        for (OutboxEvent event : pending) {
            try {
                emitter.send(toMessage(event));
                markPublished(event.id);
                sent++;
            } catch (Exception e) {
                // Leave published_at null and stop the batch. The next poll resumes
                // from the same row, which preserves per-aggregate ordering.
                LOG.errorf(e, "Failed to relay outbox event %s (%s); will retry",
                        event.id, event.eventType);
                break;
            }
        }
        return sent;
    }

    private Message<String> toMessage(OutboxEvent event) {
        // Headers are for infrastructure that routes without deserialising. The
        // envelope inside the payload remains the authoritative copy of both the
        // event type and the agency.
        var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(event.aggregateId.toString())
                .withHeaders(new RecordHeaders()
                        .add(Topics.AGENCY_HEADER, event.agencyId.getBytes(StandardCharsets.UTF_8))
                        .add("x-event-type", event.eventType.getBytes(StandardCharsets.UTF_8)))
                .build();

        return Message.of(event.payload).addMetadata(metadata);
    }

    private List<OutboxEvent> fetchPending() {
        return QuarkusTransaction.requiringNew().call(() ->
                OutboxEvent.<OutboxEvent>find("publishedAt is null", Sort.by("createdAt"))
                        .page(0, BATCH_SIZE)
                        .list());
    }

    private void markPublished(UUID id) {
        QuarkusTransaction.requiringNew().run(() ->
                OutboxEvent.update("publishedAt = ?1 where id = ?2", Instant.now(), id));
    }
}
