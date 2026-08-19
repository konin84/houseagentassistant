package com.digitalpartner.houseagent.notification.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Where to reach one person, and what they want to hear about.
 *
 * <p>Keyed on the platform-wide party id, not on an agency. A landlord working with
 * three agencies is one person with one address; giving each agency its own copy would
 * mean three emails about one payment, and an address corrected in one place and stale
 * in the other two.
 *
 * <p>Nothing here is agency-scoped, which is why this service configures no
 * multi-tenancy at all - see the package documentation on the consumer.
 */
@Entity
@Table(name = "contact")
public class Contact extends PanacheEntityBase {

    /** The landlord or renter this belongs to. */
    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    public UUID partyId;

    @Column(name = "email", nullable = false, length = 320)
    public String email;

    @Column(name = "display_name", length = 200)
    public String displayName;

    /** BCP-47. A rent notice in the wrong language gets ignored, not translated. */
    @Column(name = "locale", nullable = false, length = 16)
    public String locale = "fr";

    /**
     * Preferences, not switches for the platform's convenience.
     *
     * <p>Checked before a message is composed rather than before it is sent, so
     * declining a notification means none is written to the delivery log either -
     * there is no half-record of a message nobody wanted.
     */
    @Column(name = "notify_on_payment", nullable = false)
    public boolean notifyOnPayment = true;

    @Column(name = "notify_on_arrears", nullable = false)
    public boolean notifyOnArrears = true;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    public boolean wants(String eventType) {
        return switch (eventType) {
            case "PaymentSettled" -> notifyOnPayment;
            case "RentOverdue" -> notifyOnArrears;
            // An event type this service has no preference for is sent. Silence by
            // default would mean a new notification type quietly reaching nobody.
            default -> true;
        };
    }
}
