package com.digitalpartner.houseagent.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Events published by lease-service.
 * <p>
 * property-service consumes these to flip a house between AVAILABLE and OCCUPIED,
 * which is what keeps let houses off the marketplace. The projection is eventually
 * consistent; the authoritative guard against double-letting is the overlapping-lease
 * exclusion constraint inside lease-service, not this event.
 */
public final class LeaseEvents {

    /**
     * @param houseReference human-readable house label, and {@code renterName} the
     *                       name on the contract. Both are carried so payment-service
     *                       can raise a legible invoice, and notification-service can
     *                       write "Ama paid rent for Villa Cocody" rather than quoting
     *                       two UUIDs at a landlord. Neither service can look them up:
     *                       they belong to lease-service, and an invoice run must not
     *                       fail because another service is down.
     */
    public record LeaseSigned(
            UUID leaseId,
            UUID houseId,
            String agencyId,
            UUID renterId,
            String renterName,
            UUID landlordId,
            String houseReference,
            BigDecimal rentAmount,
            String currency,
            PaymentCadence cadence,
            int dueDayOfMonth,
            LocalDate startDate,
            LocalDate endDate,
            Instant occurredAt) {
    }

    /**
     * The renter has moved in.
     *
     * <p>Does not change whether the house is listable - it was already unavailable
     * from the moment the lease was signed - but it does move the house from RESERVED
     * to OCCUPIED, so agents see an accurate state rather than a permanent "reserved".
     */
    public record LeaseActivated(
            UUID leaseId,
            UUID houseId,
            String agencyId,
            Instant occurredAt) {
    }

    /** Covers normal expiry, early termination and cancellation before move-in. */
    public record LeaseEnded(
            UUID leaseId,
            UUID houseId,
            String agencyId,
            String reason,
            Instant occurredAt) {
    }

    /** How often rent falls due. Drives both invoicing and reminder scheduling. */
    public enum PaymentCadence {
        MONTHLY,
        QUARTERLY,
        BIANNUAL,
        ANNUAL
    }

    private LeaseEvents() {
    }
}
