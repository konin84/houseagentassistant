package com.digitalpartner.houseagent.property.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A physical house managed by one agency on behalf of one landlord.
 * <p>
 * This is the agency-facing aggregate: every read and write of it is confined to the
 * caller's own agency. The public, cross-agency view of a house is a separate
 * projection, {@link MarketplaceListing} - see that class for why.
 */
@Entity
@Table(name = "house")
public class House extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    /**
     * The agency that manages this house - the SaaS tenant discriminator.
     * <p>
     * Hibernate populates it from the current tenant on insert and appends it to the
     * WHERE clause of every query against this entity. Nothing in application code
     * should ever set or filter on it by hand; if you find yourself doing that, the
     * query almost certainly belongs on the marketplace projection instead.
     */
    @TenantId
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    /** The landlord who owns it. A platform-wide party id, not agency-scoped. */
    @Column(name = "landlord_id", nullable = false)
    public UUID landlordId;

    @Column(name = "title", nullable = false, length = 200)
    public String title;

    @Column(name = "description", length = 4000)
    public String description;

    @Embedded
    public Address address;

    @Column(name = "bedrooms", nullable = false)
    public int bedrooms;

    @Column(name = "bathrooms", nullable = false)
    public int bathrooms;

    @Column(name = "size_sqm")
    public Integer sizeSqm;

    /**
     * Rent per month. BigDecimal rather than double - money in binary floating point
     * is a rounding bug waiting for the first invoice.
     */
    @Column(name = "price_per_month", nullable = false, precision = 14, scale = 2)
    public BigDecimal pricePerMonth;

    /** ISO-4217, e.g. XOF, XAF, EUR. Stored per house: agencies may span countries. */
    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public AvailabilityStatus status = AvailabilityStatus.AVAILABLE;

    /**
     * Whether the agent has chosen to advertise this house.
     * <p>
     * Distinct from {@link #status}: a house can be AVAILABLE but deliberately not
     * advertised (word-of-mouth letting, or still being photographed). Both must be
     * true for the house to reach the marketplace.
     */
    @Column(name = "published", nullable = false)
    public boolean published = false;

    /**
     * Batched rather than eager: listing 20 houses would otherwise issue 21 queries.
     * With a batch size, Hibernate loads every gallery in one extra round trip.
     */
    @OneToMany(mappedBy = "house", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    public List<HouseImage> images = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Guards against two agents overwriting each other's edits to the same house.
     * Cheap to add now, impossible to retrofit without a migration and a bug report.
     */
    @Version
    @Column(name = "version", nullable = false)
    public long version;

    /** True when this house is allowed to appear in public search results. */
    public boolean isVisibleToPublic() {
        return published && status.isListable();
    }

    public HouseImage coverImage() {
        return images.stream()
                .filter(i -> i.cover)
                .findFirst()
                .orElseGet(() -> images.isEmpty() ? null : images.get(0));
    }
}
