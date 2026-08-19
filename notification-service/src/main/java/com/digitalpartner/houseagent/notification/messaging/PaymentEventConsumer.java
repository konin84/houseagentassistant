package com.digitalpartner.houseagent.notification.messaging;

import com.digitalpartner.houseagent.common.events.EventEnvelope;
import com.digitalpartner.houseagent.notification.domain.Contact;
import com.digitalpartner.houseagent.notification.domain.Notification;
import com.digitalpartner.houseagent.notification.service.ContactDirectory;
import com.digitalpartner.houseagent.notification.service.EmailDispatcher;
import com.digitalpartner.houseagent.notification.service.MessageComposer;
import com.digitalpartner.houseagent.notification.service.MessageComposer.Audience;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns payment events into messages people receive.
 *
 * <h2>Why this service has no tenant filter</h2>
 *
 * Every other service here scopes its data by agency. This one deliberately does not,
 * and configures no multi-tenancy at all. Its subjects are landlords and renters, who
 * are platform-wide: one landlord working with three agencies is one person with one
 * address who should receive one email about one payment. An agency discriminator on
 * {@code contact} would mean three copies of that address, corrected in one place and
 * stale in the other two.
 *
 * <p>A pleasant consequence is that this consumer needs no
 * {@code @ActivateRequestContext}: with no tenant to resolve, Hibernate opens a session
 * on a Kafka thread without one.
 *
 * <h2>Redelivery</h2>
 *
 * Payment events arrive at least once. {@code (event_id, party_id)} is unique in the
 * delivery log, so the second copy of a settlement finds its row already written and
 * sends nothing. Keyed on the pair rather than the event alone because one event
 * legitimately reaches two people - unpaid rent concerns both the renter who owes it
 * and the landlord whose money is late.
 */
@ApplicationScoped
public class PaymentEventConsumer {

    private static final Logger LOG = Logger.getLogger(PaymentEventConsumer.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ContactDirectory contacts;

    @Inject
    MessageComposer composer;

    @Inject
    EmailDispatcher dispatcher;

    @Incoming("payment-events")
    @Blocking
    public void onPaymentEvent(String raw) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(raw, new TypeReference<EventEnvelope<JsonNode>>() {
            });
        } catch (Exception e) {
            // Acknowledge rather than rethrow. A message this service cannot parse will
            // never become parseable, and failing it forever would block every
            // well-formed event queued behind it.
            LOG.errorf(e, "Discarding unparseable payment event: %s", raw);
            return;
        }

        if (envelope.eventId() == null || envelope.payload() == null) {
            LOG.errorf("Discarding payment event with no id or payload: %s", raw);
            return;
        }

        for (Recipient recipient : recipientsOf(envelope)) {
            UUID logged = logIfNew(envelope, recipient);
            // Attempt delivery immediately, outside the logging transaction. A failure
            // here is not lost - the row is committed and the retry sweep will find it.
            if (logged != null) {
                dispatcher.deliver(logged);
            }
        }
    }

    /**
     * Who should hear about this event.
     *
     * <p>A settlement concerns the landlord: it is their money that arrived. Arrears
     * concern both parties, and the renter first - they are the one who can fix it.
     */
    private List<Recipient> recipientsOf(EventEnvelope<JsonNode> envelope) {
        JsonNode payload = envelope.payload();
        List<Recipient> recipients = new ArrayList<>();

        switch (envelope.eventType()) {
            case "PaymentSettled" -> addIfPresent(recipients, payload, "landlordId", Audience.LANDLORD);
            case "RentOverdue" -> {
                addIfPresent(recipients, payload, "renterId", Audience.RENTER);
                addIfPresent(recipients, payload, "landlordId", Audience.LANDLORD);
            }
            // Unknown types are ignored rather than failed: payment-service must be able
            // to publish a new event without this service being redeployed first.
            default -> LOG.debugf("No notification defined for %s", envelope.eventType());
        }
        return recipients;
    }

    /**
     * Writes the delivery-log row, or returns null if there is nothing to send.
     *
     * @return the id of a newly logged notification, or null when the event was already
     *         handled, the party has no contact details, or they have opted out
     */
    private UUID logIfNew(EventEnvelope<JsonNode> envelope, Recipient recipient) {
        Optional<Contact> maybeContact = contacts.find(recipient.partyId());
        if (maybeContact.isEmpty()) {
            // Not an error worth failing the message for: an agency may simply not have
            // onboarded this landlord's address yet. Logged loudly enough to notice.
            LOG.warnf("No contact details for party %s; %s not sent",
                    recipient.partyId(), envelope.eventType());
            return null;
        }

        Contact contact = maybeContact.get();
        if (!contact.wants(envelope.eventType())) {
            LOG.debugf("Party %s has opted out of %s", recipient.partyId(), envelope.eventType());
            return null;
        }

        return QuarkusTransaction.requiringNew().call(() -> {
            // The unique index is the real guarantee; this check is what makes the
            // ordinary redelivery cheap rather than an aborted transaction.
            long already = Notification.count("eventId = ?1 and partyId = ?2",
                    envelope.eventId(), recipient.partyId());
            if (already > 0) {
                LOG.debugf("Event %s already notified to %s", envelope.eventId(), recipient.partyId());
                return null;
            }

            MessageComposer.Message message =
                    composer.compose(envelope.eventType(), envelope.payload(), contact,
                            recipient.audience());

            Notification notification = new Notification();
            notification.eventId = envelope.eventId();
            notification.eventType = envelope.eventType();
            notification.agencyId = envelope.agencyId();
            notification.partyId = recipient.partyId();
            notification.recipient = contact.email;
            notification.subject = message.subject();
            notification.body = message.body();
            notification.persist();

            return notification.id;
        });
    }

    private static void addIfPresent(List<Recipient> recipients, JsonNode payload,
                                     String field, Audience audience) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return;
        }
        try {
            recipients.add(new Recipient(UUID.fromString(node.asText()), audience));
        } catch (IllegalArgumentException e) {
            // A malformed id is a bug upstream, not something to retry into.
        }
    }

    private record Recipient(UUID partyId, Audience audience) {
    }
}
