package com.digitalpartner.houseagent.agency.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A rental agency: the SaaS customer.
 *
 * <p>Until this existed an agency was only a string in a token - every other service
 * filters by it, and nothing anywhere could say what it was called. This is the record
 * behind the discriminator.
 *
 * <p>No {@code @TenantId}, unlike almost everything else on the platform. This table
 * <em>is</em> the tenants, so filtering it by the current tenant would leave a platform
 * admin unable to see the thing they administer. Access is an explicit predicate in
 * {@code AgencyRegistry} instead.
 */
@Entity
@Table(name = "agency")
public class Agency extends PanacheEntityBase {

    /**
     * The same value that appears as the {@code agency_id} claim, and as the tenant
     * discriminator in every other service.
     *
     * <p>A slug rather than a generated id because it is read by humans in tokens and
     * logs. It is chosen once and never changes - renaming it would orphan every house,
     * lease and invoice that was filed under the old one.
     */
    @Id
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @Column(name = "name", nullable = false, length = 200)
    public String name;

    @Column(name = "city", length = 120)
    public String city;

    @Column(name = "country_code", length = 2)
    public String countryCode;

    @Column(name = "contact_email", length = 320)
    public String contactEmail;

    @Column(name = "contact_phone", length = 40)
    public String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public AgencyStatus status = AgencyStatus.ACTIVE;

    /**
     * What they are entitled to.
     *
     * <p>Only the plan is stored, never the ceiling it implies. A copy of the number on
     * every agency row would turn "raise the free tier from five to ten" into an UPDATE
     * across every customer rather than a one-line change.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 32)
    public SubscriptionPlan plan = SubscriptionPlan.FREE;

    @Column(name = "plan_changed_at", nullable = false)
    public Instant planChangedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
