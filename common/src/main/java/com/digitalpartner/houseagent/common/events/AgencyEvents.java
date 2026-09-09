package com.digitalpartner.houseagent.common.events;

import java.time.Instant;

/**
 * Events published by agency-service.
 *
 * <p>property-service consumes {@link AgencyPlanChanged} to know how many houses an
 * agency may have. That link is deliberately asynchronous: asking agency-service on
 * every house creation would mean an agency-service outage stopping every agency on the
 * platform from listing anything, and nothing else here calls anything.
 */
public final class AgencyEvents {

    /**
     * An agency's subscription, as it now stands.
     *
     * <p>Carries absolute state rather than a delta - the plan <em>is</em> this, not
     * "upgraded by one tier". Delivery is at least once, so a consumer that applied
     * changes relatively would drift the first time a message arrived twice.
     *
     * <p>Emitted when an agency is registered as well as when its plan changes. A
     * consumer therefore never has to guess what a newly created agency is allowed.
     *
     * @param maxHouses the ceiling, or null for unlimited. Null rather than a large
     *                  number so that "unlimited" cannot be mistaken for a limit
     *                  somebody chose, and so no arithmetic accidentally applies to it.
     */
    public record AgencyPlanChanged(
            String agencyId,
            String plan,
            Integer maxHouses,
            Instant occurredAt) {
    }

    private AgencyEvents() {
    }
}
