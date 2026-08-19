package com.digitalpartner.houseagent.property.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The public, cross-agency view of a listed house.
 *
 * <h2>Why this is a separate table rather than a query over {@link House}</h2>
 *
 * Public search is deliberately <em>not</em> tenant-scoped: a renter browsing the
 * marketplace must see houses from every agency at once. That is the exact opposite
 * of the rule {@code House} enforces, and the two cannot be satisfied by one entity -
 * {@code House} carries {@code @TenantId}, so Hibernate will always narrow it to a
 * single agency.
 *
 * <p>Deliberately absent here: {@code landlordId}, internal notes, agency margin.
 * A projection that copies every column is a data leak with extra steps. The
 * {@code agencyId} below is a plain column, present so a renter can see who manages
 * the house - it is <em>not</em> a {@code @TenantId} and must never become one.
 *
 * <p>In Phase 1 this table is maintained in the same transaction as the house it
 * mirrors. In Phase 2, when lease-service starts emitting events, it becomes an event
 * consumer instead and the write path here goes away.
 */
@Entity
@Table(name = "marketplace_listing")
public class MarketplaceListing extends PanacheEntityBase {

    /** Same identifier as the house it mirrors, so the projection is idempotent. */
    @Id
    @Column(name = "house_id", updatable = false, nullable = false)
    public UUID houseId;

    @Column(name = "agency_id", nullable = false, length = 64)
    public String agencyId;

    @Column(name = "title", nullable = false, length = 200)
    public String title;

    @Column(name = "description", length = 4000)
    public String description;

    @Column(name = "district", length = 120)
    public String district;

    @Column(name = "city", nullable = false, length = 120)
    public String city;

    @Column(name = "country_code", nullable = false, length = 2)
    public String countryCode;

    @Column(name = "bedrooms", nullable = false)
    public int bedrooms;

    @Column(name = "bathrooms", nullable = false)
    public int bathrooms;

    @Column(name = "size_sqm")
    public Integer sizeSqm;

    @Column(name = "price_per_month", nullable = false, precision = 14, scale = 2)
    public BigDecimal pricePerMonth;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    /** Cloudinary public id of the cover photo. Rendered into a URL at read time. */
    @Column(name = "cover_image_public_id", length = 512)
    public String coverImagePublicId;

    @Column(name = "listed_at", nullable = false)
    public Instant listedAt = Instant.now();

    /** Rebuilds this row from the house it mirrors. Safe to call repeatedly. */
    public MarketplaceListing copyFrom(House house) {
        this.houseId = house.id;
        this.agencyId = house.agencyId;
        this.title = house.title;
        this.description = house.description;
        this.district = house.address != null ? house.address.district : null;
        this.city = house.address != null ? house.address.city : null;
        this.countryCode = house.address != null ? house.address.countryCode : null;
        this.bedrooms = house.bedrooms;
        this.bathrooms = house.bathrooms;
        this.sizeSqm = house.sizeSqm;
        this.pricePerMonth = house.pricePerMonth;
        this.currency = house.currency;
        HouseImage cover = house.coverImage();
        this.coverImagePublicId = cover != null ? cover.cloudinaryPublicId : null;
        return this;
    }
}
