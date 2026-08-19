package com.digitalpartner.houseagent.notification.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One message this service decided to send, and what became of it.
 *
 * <h2>Why the log is written before the send</h2>
 *
 * Composing and sending in one step means a crash mid-send either loses the message or
 * sends it twice, and there is no way afterwards to tell which. Writing the row first,
 * committing, and then attempting delivery makes the outcome recoverable: whatever
 * happens, there is a record saying what was meant to go out and whether it did.
 *
 * <p>{@link #eventId} carries a unique constraint, and that is what makes this service
 * safe under at-least-once delivery. The same {@code PaymentSettled} will arrive twice;
 * the second attempt to log it is refused by the database, so the landlord is emailed
 * once.
 */
@Entity
@Table(name = "notification")
public class Notification extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    /** Envelope id of the event that caused this. The idempotency key. */
    @Column(name = "event_id", nullable = false, updatable = false)
    public UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    public String eventType;

    /** Stored for support and debugging only. Never used to narrow a query. */
    @Column(name = "agency_id", length = 64)
    public String agencyId;

    @Column(name = "party_id", nullable = false)
    public UUID partyId;

    /**
     * Resolved at compose time and then frozen.
     *
     * <p>A retry three hours later must go where the message was addressed, not
     * wherever the contact has been edited to since - otherwise a redelivery quietly
     * becomes a different message to a different person.
     */
    @Column(name = "recipient", nullable = false, length = 320)
    public String recipient;

    @Column(name = "subject", nullable = false, length = 300)
    public String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    public String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "last_error", length = 500)
    public String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    public Instant sentAt;

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    /**
     * @param maxAttempts after which the message is abandoned rather than retried
     *                    forever. A permanently bad address would otherwise sit at the
     *                    front of the queue delaying everything behind it.
     */
    public void markFailed(String error, int maxAttempts) {
        this.attempts++;
        // Truncated rather than allowed to overflow the column: an SMTP server that
        // returns a wall of text must not turn a delivery failure into a write failure.
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.status = this.attempts >= maxAttempts
                ? NotificationStatus.ABANDONED
                : NotificationStatus.FAILED;
    }
}
