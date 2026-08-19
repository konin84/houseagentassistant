package com.digitalpartner.houseagent.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Events published by property-service. Records, so they are immutable and map to
 * JSON without extra plumbing.
 */
public final class HouseEvents {

    /** A house was published to the public marketplace. */
    public record HouseListed(
            UUID houseId,
            String agencyId,
            UUID landlordId,
            String city,
            BigDecimal pricePerMonth,
            String currency,
            Instant occurredAt) {
    }

    /** A house was withdrawn from the marketplace, whether let, paused or removed. */
    public record HouseDelisted(
            UUID houseId,
            String agencyId,
            String reason,
            Instant occurredAt) {
    }

    private HouseEvents() {
    }
}
