package com.digitalpartner.houseagent.notification.service;

import com.digitalpartner.houseagent.notification.domain.Contact;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a party id into somewhere to send a message.
 *
 * <p>Events carry ids, never addresses - see {@code PaymentEvents}. This is the only
 * place that closes that gap, so there is one answer to "where does this person's mail
 * go" rather than one per event type.
 *
 * <p>Keycloak will eventually be the source of truth. Until then an agency onboards a
 * landlord's address here and the landlord can correct it themselves.
 */
@ApplicationScoped
public class ContactDirectory {

    @Transactional
    public Optional<Contact> find(UUID partyId) {
        return Optional.ofNullable(Contact.findById(partyId));
    }

    @Transactional
    public Contact upsert(UUID partyId, String email, String displayName, String locale,
                          Boolean notifyOnPayment, Boolean notifyOnArrears) {
        Contact contact = Contact.findById(partyId);
        boolean isNew = contact == null;
        if (isNew) {
            contact = new Contact();
            contact.partyId = partyId;
        }
        if (email != null && !email.isBlank()) {
            contact.email = email.trim();
        }
        if (displayName != null) {
            contact.displayName = displayName;
        }
        if (locale != null && !locale.isBlank()) {
            contact.locale = locale;
        }
        if (notifyOnPayment != null) {
            contact.notifyOnPayment = notifyOnPayment;
        }
        if (notifyOnArrears != null) {
            contact.notifyOnArrears = notifyOnArrears;
        }
        contact.updatedAt = Instant.now();

        if (contact.email == null || contact.email.isBlank()) {
            // Refuse a contact with nowhere to send to. Persisting one would produce a
            // party this service believes it can reach and silently cannot.
            throw new IllegalContactException("A contact must have an email address");
        }

        // Persisted only once the row is complete. Persisting an empty entity first and
        // filling it in afterwards lets Hibernate flush the insert in between, which
        // fails against the not-null constraint on the address.
        if (isNew) {
            contact.persist();
        }
        return contact;
    }

    @Transactional
    public void delete(UUID partyId) {
        Contact.deleteById(partyId);
    }
}
