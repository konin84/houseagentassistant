package com.digitalpartner.houseagent.payment.domain;

import com.digitalpartner.houseagent.common.events.LeaseEvents.PaymentCadence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * This service's own copy of the lease terms it bills against.
 *
 * <h2>Why a replica rather than a call to lease-service</h2>
 *
 * Two reasons, and the second is the important one. An invoice run that queried
 * lease-service would fail whenever lease-service was down, turning one service's
 * outage into missed rent. And the terms in force when an invoice was raised are a
 * historical fact: if a lease is later corrected, invoices already issued must not
 * silently change to match.
 *
 * <p>Built entirely from lease events. Nothing here is ever edited through this
 * service's API - there is no endpoint that writes it, because the authority for
 * these fields is lease-service and two writers would guarantee they disagree.
 *
 * <p>No {@code @TenantId}: the invoice generator sweeps every agency's leases on one
 * schedule, and establishes each agency explicitly before writing anything.
 */
@Entity
@Table(name = "lease_billing")
public class LeaseBilling extends PanacheEntityBase {

    /** Same id as the lease in lease-service, which makes replication idempotent. */
    @Id
    @Column(name = "lease_id", nullable = false, updatable = false)
    public UUID leaseId;

    @Column(name = "agency_id", nullable = false, length = 64)
    public String agencyId;

    @Column(name = "house_id", nullable = false)
    public UUID houseId;

    /** Human-readable label, so an invoice names a house rather than a UUID. */
    @Column(name = "house_reference", length = 200)
    public String houseReference;

    @Column(name = "renter_id", nullable = false)
    public UUID renterId;

    @Column(name = "renter_name", length = 200)
    public String renterName;

    @Column(name = "landlord_id", nullable = false)
    public UUID landlordId;

    @Column(name = "rent_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal rentAmount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false, length = 20)
    public PaymentCadence cadence;

    @Column(name = "due_day_of_month", nullable = false)
    public int dueDayOfMonth;

    @Column(name = "start_date", nullable = false)
    public LocalDate startDate;

    /** Null for an open-ended lease, which is billed until it is ended. */
    @Column(name = "end_date")
    public LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false, length = 20)
    public BillingStatus billingStatus = BillingStatus.ACTIVE;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /** Months between due dates. Drives both the invoice period and its amount. */
    public int monthsPerPeriod() {
        return switch (cadence) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case BIANNUAL -> 6;
            case ANNUAL -> 12;
        };
    }

    /**
     * The first date rent falls due on or after the lease start.
     *
     * <p>Duplicated from lease-service's {@code PaymentModality} rather than shared.
     * It is three lines, and a service that imported another's billing arithmetic
     * would be unable to fix a bug in its own invoices without redeploying both.
     */
    public LocalDate firstDueDate() {
        LocalDate candidate = startDate.withDayOfMonth(dueDayOfMonth);
        return candidate.isBefore(startDate) ? candidate.plusMonths(1) : candidate;
    }
}
