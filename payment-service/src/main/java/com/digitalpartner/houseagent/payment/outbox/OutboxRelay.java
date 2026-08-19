package com.digitalpartner.houseagent.payment.outbox;

import com.digitalpartner.houseagent.common.events.Topics;
import com.digitalpartner.houseagent.payment.service.AgencyScan;
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
 * path: a broker outage delays a landlord's email but never fails a payment that has
 * already settled with the provider.
 *
 * <p>Delivery is <em>at least once</em>. If the broker accepts a message and this
 * process dies before marking the row published, the event is sent again after
 * restart. Marking published before sending would lose events outright, which for a
 * settlement notification is the worse failure. Consumers must therefore be
 * idempotent - notification-service keys its delivery log on the event id for exactly
 * this reason.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    /** Bounded so one poll cannot pull an unbounded backlog into memory. */
    private static final int BATCH_SIZE = 100;

    @Inject
    @Channel("payment-events")
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
     * <p>Separate from the schedule so tests can drive it explicitly rather than
     * waiting on a timer.
     */
    public int relayPending() {
        // Hibernate needs a tenant to open a session even though outbox_event carries no
        // discriminator, and Quarkus only resolves one inside a request context. See
        // AgencyScan.inRequestContext for why this cannot be left to the caller.
        return AgencyScan.withoutTenant(() -> {
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
        });
    }

    private Message<String> toMessage(OutboxEvent event) {
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
