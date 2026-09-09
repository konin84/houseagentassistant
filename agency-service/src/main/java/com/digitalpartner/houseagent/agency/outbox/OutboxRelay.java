package com.digitalpartner.houseagent.agency.outbox;

import com.digitalpartner.houseagent.common.events.Topics;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
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
import java.util.function.Supplier;

/**
 * Moves committed outbox rows to Kafka.
 *
 * <p>Runs on a timer rather than reacting to writes, so a broker outage delays
 * property-service learning about a plan change but never fails the change itself.
 *
 * <p>Delivery is <em>at least once</em>. The consumer applies absolute state - the plan
 * <em>is</em> this - so a repeated message rewrites the same row rather than compounding.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    /** Bounded so one poll cannot pull an unbounded backlog into memory. */
    private static final int BATCH_SIZE = 100;

    @Inject
    @Channel("agency-events")
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
     * <p>Public and separate from the schedule so tests can drive it explicitly rather
     * than waiting on a timer.
     */
    public int relayPending() {
        return inRequestContext(() -> {
            List<OutboxEvent> pending = fetchPending();
            int sent = 0;
            for (OutboxEvent event : pending) {
                try {
                    emitter.send(toMessage(event));
                    markPublished(event.id);
                    sent++;
                } catch (Exception e) {
                    // Leave published_at null and stop the batch. The next poll resumes
                    // from the same row, which preserves per-agency ordering - and for a
                    // plan, order is the difference between an upgrade and a downgrade.
                    LOG.errorf(e, "Failed to relay outbox event %s (%s); will retry",
                            event.id, event.eventType);
                    break;
                }
            }
            return sent;
        });
    }

    /**
     * Hibernate needs a tenant identifier to open a session even where nothing is
     * tenant-filtered, and Quarkus only resolves one inside a request context. The
     * scheduler provides one; a test calling this directly does not, so it is activated
     * here rather than left to whoever calls.
     */
    private static <T> T inRequestContext(Supplier<T> work) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return work.get();
        }
        requestContext.activate();
        try {
            return work.get();
        } finally {
            requestContext.terminate();
        }
    }

    private Message<String> toMessage(OutboxEvent event) {
        var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                // Keyed by agency, so two changes to one agency's plan stay in order.
                .withKey(event.aggregateId)
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
