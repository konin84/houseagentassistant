package com.digitalpartner.houseagent.property.service;

import com.digitalpartner.houseagent.property.domain.House;
import com.digitalpartner.houseagent.property.domain.MarketplaceListing;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps the public {@link MarketplaceListing} projection in step with the houses it
 * mirrors.
 *
 * <p>This is the single place that decides whether a house is publicly visible, which
 * is what makes "only houses that are not rented get listed" one rule rather than a
 * condition scattered across every query.
 *
 * <p>Phase 1 calls this synchronously, inside the same transaction as the house
 * write, so the projection cannot drift. In Phase 2, once lease-service publishes
 * lease events, the same method gets driven by a Kafka consumer instead - at which
 * point the projection becomes eventually consistent and the exclusion constraint in
 * lease-service becomes the authoritative guard against double-letting.
 */
@ApplicationScoped
public class ListingProjector {

    /**
     * Publishes, refreshes or withdraws the listing for a house depending on its
     * current state. Idempotent: calling it twice with the same house is a no-op the
     * second time.
     */
    public void sync(House house) {
        MarketplaceListing existing = MarketplaceListing.findById(house.id);

        if (!house.isVisibleToPublic()) {
            if (existing != null) {
                existing.delete();
            }
            return;
        }

        if (existing == null) {
            new MarketplaceListing().copyFrom(house).persist();
        } else {
            // listedAt is intentionally left at its original value; a price edit
            // should not push a stale house back to the top of "newest first".
            existing.copyFrom(house);
        }
    }

    /** Removes the listing outright, used when the house itself is deleted. */
    public void remove(House house) {
        MarketplaceListing.deleteById(house.id);
    }
}
