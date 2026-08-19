package com.digitalpartner.houseagent.payment.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a landlord has actually been paid, across every agency that manages a house
 * for them.
 *
 * <p>Carries gross, commission and net separately rather than only the net figure. A
 * landlord who is told "you received 139,500" and separately "the rent is 150,000"
 * will ask where the difference went, and the answer should be in the same row rather
 * than reconstructed from a rate that may since have changed.
 *
 * <p>No {@code @TenantId}, for the same reason as {@link RenterInvoiceView}: the
 * explicit {@code landlord_id} predicate is the access control.
 */
@Entity
@Table(name = "landlord_earning_view")
public class LandlordEarningView extends PanacheEntityBase {

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false)
    public UUID paymentId;

    @Column(name = "landlord_id", nullable = false)
    public UUID landlordId;

    @Column(name = "agency_id", nullable = false, length = 64)
    public String agencyId;

    @Column(name = "lease_id", nullable = false)
    public UUID leaseId;

    @Column(name = "house_reference", length = 200)
    public String houseReference;

    @Column(name = "renter_name", length = 200)
    public String renterName;

    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal grossAmount;

    /** Zero when the agency does not collect on the landlord's behalf. */
    @Column(name = "commission_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal commissionAmount;

    @Column(name = "net_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal netAmount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Column(name = "settled_at", nullable = false)
    public Instant settledAt;
}
