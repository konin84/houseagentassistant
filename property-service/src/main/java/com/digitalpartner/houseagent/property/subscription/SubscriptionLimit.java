package com.digitalpartner.houseagent.property.subscription;

import com.digitalpartner.houseagent.property.domain.House;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * The one place a subscription limit is enforced.
 *
 * <p>Counting rather than a stored total. A running count would need updating on every
 * create and delete, and the first time one of those paths missed it the number would be
 * wrong in a way nothing would notice until an agency was refused a house they were
 * entitled to. {@code House} is tenant-filtered, so counting it is already scoped to the
 * caller's own agency without saying so.
 */
@ApplicationScoped
public class SubscriptionLimit {

    private static final Logger LOG = Logger.getLogger(SubscriptionLimit.class);

    /**
     * Refuses the house if the agency is at its ceiling.
     *
     * <p>Called before the insert rather than after, so a refused attempt leaves
     * nothing behind.
     *
     * @param agencyId the caller's own agency, taken from their token
     */
    public void requireRoomForAnotherHouse(String agencyId) {
        AgencyPlan plan = AgencyPlan.findById(agencyId);
        if (plan == null) {
            plan = AgencyPlan.unknownAgencyDefault(agencyId);
            // Worth a warning rather than silence: being at the free tier because that
            // is the plan and being there because an event never arrived look identical
            // to the agency, and only one of them is somebody's problem to fix.
            LOG.warnf("No plan known for agency %s; assuming %s. Is agency-service "
                            + "publishing, and is the broker up?", agencyId, plan.plan);
        }

        if (plan.isUnlimited()) {
            return;
        }

        long held = House.count();
        if (held >= plan.maxHouses) {
            throw new SubscriptionLimitReachedException(plan.plan, plan.maxHouses, held);
        }
    }
}
