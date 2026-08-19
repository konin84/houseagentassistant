package com.digitalpartner.houseagent.payment.messaging;

import com.digitalpartner.houseagent.common.events.EventEnvelope;
import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.payment.service.LeaseBillingReplicator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Keeps this service's billing replica in step with lease-service.
 *
 * <p>Nothing here calls lease-service. A lease is signed there, an event arrives here,
 * and from that moment payment-service can raise invoices whether or not lease-service
 * is running.
 *
 * <h2>Establishing the tenant</h2>
 *
 * There is no HTTP request and therefore no JWT, so the tenant filter has nothing to
 * read. Each handler runs inside {@link AgencyContext#runWith} using the agency carried
 * in the event.
 *
 * <p>{@code @ActivateRequestContext} is what makes that reachable at all: Quarkus only
 * consults a {@code TenantResolver} when a CDI request context is active, and without
 * one Hibernate is handed a null tenant and refuses to open a session - before
 * {@link AgencyContext} is ever consulted. This bites even though {@code LeaseBilling}
 * itself carries no discriminator, because it is the session, not the entity, that
 * needs a tenant.
 *
 * <h2>Redelivery</h2>
 *
 * Delivery is at least once. Every handler writes absolute state keyed on the lease id,
 * so a repeated event rewrites the same row rather than adding another.
 */
@ApplicationScoped
public class LeaseEventConsumer {

    private static final Logger LOG = Logger.getLogger(LeaseEventConsumer.class);

    @Inject
    ObjectMapper objectMapper;

    /**
     * The writes live on their own bean deliberately: {@code @Transactional} is an
     * interceptor and would not apply to a call this class made to itself.
     */
    @Inject
    LeaseBillingReplicator replicator;

    @Incoming("lease-events")
    @Blocking
    @ActivateRequestContext
    public void onLeaseEvent(String raw) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(raw, new TypeReference<EventEnvelope<JsonNode>>() {
            });
        } catch (Exception e) {
            // Acknowledge rather than rethrow. A message this service cannot parse will
            // never become parseable on retry, and failing it forever would block every
            // well-formed event queued behind it.
            LOG.errorf(e, "Discarding unparseable lease event: %s", raw);
            return;
        }

        if (envelope.agencyId() == null || envelope.agencyId().isBlank()) {
            LOG.errorf("Discarding lease event %s with no agency", envelope.eventId());
            return;
        }

        AgencyContext.runWith(envelope.agencyId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        JsonNode payload = envelope.payload();
        UUID leaseId = payload == null ? null : uuidOf(payload);
        if (leaseId == null) {
            LOG.warnf("Lease event %s of type %s carries no leaseId",
                    envelope.eventId(), envelope.eventType());
            return;
        }

        switch (envelope.eventType()) {
            case "LeaseSigned" -> replicator.startBilling(leaseId, envelope.agencyId(), payload);
            case "LeaseEnded" -> replicator.stopBilling(leaseId);
            // LeaseActivated says the renter moved in. Rent has been owed since the
            // lease was signed, so it changes nothing here.
            default -> LOG.debugf("Ignoring lease event type %s", envelope.eventType());
        }
    }

    private static UUID uuidOf(JsonNode payload) {
        JsonNode node = payload.get("leaseId");
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
