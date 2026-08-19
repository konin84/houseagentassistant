package com.digitalpartner.houseagent.lease.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A contract binding one renter to one house on agreed payment terms, arranged by an
 * agency on behalf of a landlord.
 *
 * <p>This entity is the authoritative answer to "is this house let?". property-service
 * holds a cached view of that answer for search, but the truth lives here, guarded by
 * a database constraint rather than by application logic - see
 * {@code V1__lease_schema.sql}.
 */
@Entity
@Table(name = "lease")
public class Lease extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    /** The agency that arranged and manages this lease - the tenant discriminator. */
    @TenantId
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    /** The house in property-service. No foreign key: it is another service's table. */
    @Column(name = "house_id", nullable = false, updatable = false)
    public UUID houseId;

    /** Platform-wide party id of the renter. Not agency-scoped. */
    @Column(name = "renter_id", nullable = false, updatable = false)
    public UUID renterId;

    /** Platform-wide party id of the landlord. Not agency-scoped. */
    @Column(name = "landlord_id", nullable = false, updatable = false)
    public UUID landlordId;

    /**
     * Renter contact details captured at signing.
     *
     * <p>Denormalised on purpose. A landlord asking "who is in my house?" must get an
     * answer without lease-service calling an identity service, and the name on the
     * contract is a historical fact that should not silently change if the renter
     * later edits their profile.
     */
    @Column(name = "renter_name", nullable = false, length = 200)
    public String renterName;

    @Column(name = "renter_phone", length = 40)
    public String renterPhone;

    @Column(name = "house_reference", length = 200)
    public String houseReference;

    @Embedded
    public PaymentModality modality;

    @Column(name = "start_date", nullable = false)
    public LocalDate startDate;

    /** Null for an open-ended lease, which the exclusion constraint treats as infinite. */
    @Column(name = "end_date")
    public LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public LeaseStatus status = LeaseStatus.PENDING_MOVE_IN;

    /** Why the lease ended. Null while it is still running. */
    @Column(name = "end_reason", length = 200)
    public String endReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    public long version;

    public boolean occupiesHouse() {
        return status.occupiesHouse();
    }
}
