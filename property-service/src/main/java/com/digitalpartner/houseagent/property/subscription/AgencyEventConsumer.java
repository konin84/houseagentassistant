package com.digitalpartner.houseagent.property.subscription;

import com.digitalpartner.houseagent.common.events.EventEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Keeps this service's copy of each agency's plan in step with agency-service.
 *
 * <p>Nothing here calls agency-service. A plan is published when it changes and when an
 * agency is first registered, so this service always knows what a given agency is
 * allowed without asking anybody at the moment somebody adds a house.
 *
 * <h2>Redelivery</h2>
 *
 * Delivery is at least once, and the event carries absolute state - the plan <em>is</em>
 * this - so a repeat rewrites the same row. A relative event ("upgraded one tier") would
 * drift the first time a message arrived twice, and an agency would silently end up on a
 * plan nobody sold them.
 *
 * <p>{@code @ActivateRequestContext} for the usual reason: Quarkus only resolves a
 * tenant inside a request context, and without one Hibernate refuses to open a session
 * at all - even for {@code agency_plan}, which carries no discriminator. It is the
 * session that needs a tenant, not the entity.
 */
@ApplicationScoped
public class AgencyEventConsumer {

    private static final Logger LOG = Logger.getLogger(AgencyEventConsumer.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgencyPlanReplicator replicator;

    @Incoming("agency-events")
    @Blocking
    @ActivateRequestContext
    public void onAgencyEvent(String raw) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(raw, new TypeReference<EventEnvelope<JsonNode>>() {
            });
        } catch (Exception e) {
            // Acknowledge rather than rethrow: a message this service cannot parse will
            // never become parseable, and failing it forever would block every
            // well-formed event queued behind it.
            LOG.errorf(e, "Discarding unparseable agency event: %s", raw);
            return;
        }

        if (!"AgencyPlanChanged".equals(envelope.eventType())) {
            // Ignored rather than failed, so agency-service can publish something new
            // without this service being redeployed first.
            LOG.debugf("Ignoring agency event type %s", envelope.eventType());
            return;
        }

        JsonNode payload = envelope.payload();
        String agencyId = text(payload, "agencyId");
        String plan = text(payload, "plan");
        if (agencyId == null || plan == null) {
            LOG.errorf("Discarding AgencyPlanChanged with no agency or plan: %s", raw);
            return;
        }

        JsonNode max = payload == null ? null : payload.get("maxHouses");
        Integer maxHouses = max == null || max.isNull() ? null : max.asInt();

        replicator.apply(agencyId, plan, maxHouses);
        LOG.infof("Agency %s is on %s (max houses: %s)", agencyId, plan,
                maxHouses == null ? "unlimited" : maxHouses);
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * The write, on its own bean.
     *
     * <p>{@code @Transactional} is an interceptor and does not apply when an object
     * calls its own method - a consumer invoking its own transactional method would
     * persist outside a transaction and fail at runtime, having compiled perfectly.
     */
    @ApplicationScoped
    public static class AgencyPlanReplicator {

        @Transactional
        public void apply(String agencyId, String plan, Integer maxHouses) {
            AgencyPlan existing = AgencyPlan.findById(agencyId);
            if (existing == null) {
                existing = new AgencyPlan();
                existing.agencyId = agencyId;
                existing.plan = plan;
                existing.maxHouses = maxHouses;
                existing.updatedAt = Instant.now();
                existing.persist();
                return;
            }
            existing.plan = plan;
            existing.maxHouses = maxHouses;
            existing.updatedAt = Instant.now();
        }
    }
}
