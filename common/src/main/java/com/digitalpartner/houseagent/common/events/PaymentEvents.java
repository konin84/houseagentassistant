package com.digitalpartner.houseagent.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Events published by payment-service.
 * <p>
 * notification-service consumes {@link PaymentSettled} to email the landlord. That
 * link is deliberately asynchronous: an SMTP timeout must never fail a payment that
 * has already settled with the provider.
 *
 * <h2>What these carry, and what they deliberately do not</h2>
 *
 * Each event carries enough display data - the house label, the renter's name, the
 * period covered - for a consumer to compose a message without calling back into
 * another service. What they never carry is an email address or a phone number.
 * Delivery addresses are contact details, they change independently of any payment,
 * and a Kafka topic is retained far longer than a consent to be emailed.
 * notification-service resolves the recipient from its own contact table instead.
 */
public final class PaymentEvents {

    /**
     * Rent has actually been received. The only event that may cause money to be
     * treated as paid.
     *
     * @param commissionAmount the agency's cut, zero when the agency's settlement mode
     *                         is DIRECT_TO_LANDLORD and the platform never held the
     *                         money
     * @param netAmount        what reaches the landlord, {@code amount} minus the
     *                         commission. Carried rather than left to the consumer to
     *                         compute, so an email and a payout can never disagree
     *                         about the arithmetic.
     */
    public record PaymentSettled(
            UUID paymentId,
            UUID invoiceId,
            UUID leaseId,
            String agencyId,
            UUID renterId,
            String renterName,
            UUID landlordId,
            String houseReference,
            BigDecimal amount,
            BigDecimal commissionAmount,
            BigDecimal netAmount,
            String currency,
            LocalDate periodStart,
            LocalDate periodEnd,
            String providerReference,
            Instant settledAt) {
    }

    /** Emitted by the scheduled arrears job, not by a renter action. */
    public record RentOverdue(
            UUID invoiceId,
            UUID leaseId,
            String agencyId,
            UUID renterId,
            String renterName,
            UUID landlordId,
            String houseReference,
            BigDecimal amountDue,
            String currency,
            LocalDate dueDate,
            int daysOverdue,
            Instant occurredAt) {
    }

    private PaymentEvents() {
    }
}
