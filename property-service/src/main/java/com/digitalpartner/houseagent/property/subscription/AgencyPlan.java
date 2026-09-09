package com.digitalpartner.houseagent.property.subscription;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * What one agency is allowed, as last announced by agency-service.
 *
 * <p>A replica, not a record: nothing in this service's API writes it, and the only
 * thing that does is the event consumer. The same arrangement as {@code lease_billing}
 * in payment-service, and for the same reason - enforcing a limit should not depend on
 * another service being reachable at the moment somebody adds a house.
 *
 * <p>No {@code @TenantId}. The consumer writes rows for every agency, and on a Kafka
 * thread the current tenant is none of them.
 */
@Entity
@Table(name = "agency_plan")
public class AgencyPlan extends PanacheEntityBase {

    @Id
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @Column(name = "plan", nullable = false, length = 32)
    public String plan;

    /**
     * The ceiling, or null for unlimited.
     *
     * <p>Null rather than a large number, so no comparison can accidentally treat "no
     * limit" as a limit. Every check against this has to handle the null on purpose.
     */
    @Column(name = "max_houses")
    public Integer maxHouses;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    public boolean isUnlimited() {
        return maxHouses == null;
    }

    /**
     * What an agency nobody has told us about is allowed.
     *
     * <p>The free tier, rather than nothing or everything. Refusing outright would
     * leave a newly registered agency unable to do anything until an event arrived,
     * over a message that is normally milliseconds behind; allowing everything would
     * hand out the unlimited plan to anyone whose event went missing. A brand new
     * agency is on the free plan anyway, so assuming it is very often simply correct.
     */
    public static AgencyPlan unknownAgencyDefault(String agencyId) {
        AgencyPlan plan = new AgencyPlan();
        plan.agencyId = agencyId;
        plan.plan = "FREE";
        plan.maxHouses = 5;
        return plan;
    }
}
