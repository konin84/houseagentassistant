package com.digitalpartner.houseagent.payment.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What an agency owes a landlord out of one settled payment.
 *
 * <p>Written only when the agency's {@link SettlementMode} is
 * {@code PLATFORM_COLLECTS}. Under {@code DIRECT_TO_LANDLORD} the renter pays the
 * landlord and the platform never holds the money, so there is nothing to remit and
 * no row is created at all - the absence of a payout is the record that none is owed.
 *
 * <p>One payout per payment, enforced by a unique constraint on {@code payment_id}. A
 * redelivered settlement must not accrue a second debt to the same landlord for the
 * same rent.
 */
@Entity
@Table(name = "payout")
public class Payout extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @TenantId
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @Column(name = "landlord_id", nullable = false, updatable = false)
    public UUID landlordId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    public UUID paymentId;

    @Column(name = "lease_id", nullable = false, updatable = false)
    public UUID leaseId;

    /** What the renter paid. */
    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal grossAmount;

    /** The agency's cut, at the rate configured when the payment settled. */
    @Column(name = "commission_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal commissionAmount;

    /**
     * What reaches the landlord. Stored rather than computed on read: the commission
     * rate can change, and a payout must remain the amount that was actually owed at
     * the time, not what today's rate would produce.
     */
    @Column(name = "net_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal netAmount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public PayoutStatus status = PayoutStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    public Instant settledAt;
}
