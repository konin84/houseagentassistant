package com.digitalpartner.houseagent.property.messaging;

import com.digitalpartner.houseagent.common.events.EventEnvelope;
import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.property.service.HouseAvailability;
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
 * Keeps house availability in step with lease-service.
 *
 * <p>This consumer is what turns "only unrented houses are listed" from a flag an
 * agent sets by hand into something derived from whether a lease actually exists.
 *
 * <h2>Establishing the tenant</h2>
 *
 * There is no HTTP request here and therefore no JWT, so the tenant filter has nothing
 * to read. Every handler runs inside {@link AgencyContext#runWith}, using the agency
 * carried in the event itself. Skipping that step is the classic multi-tenant data
 * leak: the write silently lands in the wrong agency, or in none, and nothing fails.
 *
 * <p>{@code @ActivateRequestContext} is what makes that reachable. Quarkus only asks
 * a {@code TenantResolver} for the tenant when a CDI request context is active - with
 * none, it hands Hibernate a null identifier and opening any session fails outright,
 * before {@link AgencyContext} is ever consulted. A consumer thread has no request of
 * its own, so it has to declare one.
 *
 * <h2>Redelivery</h2>
 *
 * Delivery is at least once, so the same event will arrive more than once. Every
 * handler sets an absolute state rather than applying a delta, which makes reprocessing
 * harmless without needing a table of seen event ids.
 */
@ApplicationScoped
public class LeaseEventConsumer {

    private static final Logger LOG = Logger.getLogger(LeaseEventConsumer.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    HouseAvailability availability;

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
        UUID houseId = houseIdOf(envelope);
        if (houseId == null) {
            LOG.warnf("Lease event %s of type %s carries no houseId",
                    envelope.eventId(), envelope.eventType());
            return;
        }

        switch (envelope.eventType()) {
            case "LeaseSigned" -> availability.markReserved(houseId);
            case "LeaseActivated" -> availability.markOccupied(houseId);
            case "LeaseEnded" -> availability.markAvailable(houseId);
            // Unknown types are ignored rather than failed: lease-service must be able
            // to publish a new event without this service being redeployed first.
            default -> LOG.debugf("Ignoring lease event type %s", envelope.eventType());
        }
    }

    private static UUID houseIdOf(EventEnvelope<JsonNode> envelope) {
        JsonNode payload = envelope.payload();
        if (payload == null || !payload.hasNonNull("houseId")) {
            return null;
        }
        try {
            return UUID.fromString(payload.get("houseId").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
