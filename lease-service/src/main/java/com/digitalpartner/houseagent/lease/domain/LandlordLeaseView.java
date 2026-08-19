package com.digitalpartner.houseagent.lease.domain;

import com.digitalpartner.houseagent.common.events.LeaseEvents.PaymentCadence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A landlord's view of their own leases: which renter is in which house, on what
 * terms.
 *
 * <h2>Why this is a projection and not a query over {@link Lease}</h2>
 *
 * This is the fourth isolation rule in the platform, and it does not fit any of the
 * other three. A landlord is a <em>platform-wide</em> principal, not an agency: they
 * may have houses with agency A in one city and agency B in another, and their token
 * carries no {@code agency_id} at all. Querying {@code Lease} as a landlord would hit
 * the {@code @TenantId} filter, resolve to the no-agency sentinel and correctly return
 * nothing.
 *
 * <p>So this table deliberately carries no {@code @TenantId}. Isolation is instead an
 * explicit {@code WHERE landlord_id = :partyId} taken from the validated token - see
 * {@code LandlordPortfolio}. That predicate is the only thing standing between one
 * landlord and every other landlord's tenancies, which is why it lives in exactly one
 * method and is asserted by its own test.
 *
 * <p>The agency that arranged the lease is shown, so a landlord can see who to call;
 * it is data, not a filter.
 */
@Entity
@Table(name = "landlord_lease_view")
public class LandlordLeaseView extends PanacheEntityBase {

    /** Same id as the lease it mirrors, which makes the projection idempotent. */
    @Id
    @Column(name = "lease_id", updatable = false, nullable = false)
    public UUID leaseId;

    @Column(name = "landlord_id", nullable = false)
    public UUID landlordId;

    @Column(name = "agency_id", nullable = false, length = 64)
    public String agencyId;

    @Column(name = "house_id", nullable = false)
    public UUID houseId;

    @Column(name = "house_reference", length = 200)
    public String houseReference;

    @Column(name = "renter_id", nullable = false)
    public UUID renterId;

    @Column(name = "renter_name", nullable = false, length = 200)
    public String renterName;

    @Column(name = "renter_phone", length = 40)
    public String renterPhone;

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

    @Column(name = "end_date")
    public LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public LeaseStatus status;

    /** Rebuilds this row from the lease it mirrors. Safe to call repeatedly. */
    public LandlordLeaseView copyFrom(Lease lease) {
        this.leaseId = lease.id;
        this.landlordId = lease.landlordId;
        this.agencyId = lease.agencyId;
        this.houseId = lease.houseId;
        this.houseReference = lease.houseReference;
        this.renterId = lease.renterId;
        this.renterName = lease.renterName;
        this.renterPhone = lease.renterPhone;
        this.rentAmount = lease.modality.rentAmount;
        this.currency = lease.modality.currency;
        this.cadence = lease.modality.cadence;
        this.dueDayOfMonth = lease.modality.dueDayOfMonth;
        this.startDate = lease.startDate;
        this.endDate = lease.endDate;
        this.status = lease.status;
        return this;
    }
}
