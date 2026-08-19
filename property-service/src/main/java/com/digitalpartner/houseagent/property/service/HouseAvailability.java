package com.digitalpartner.houseagent.property.service;

import com.digitalpartner.houseagent.property.domain.AvailabilityStatus;
import com.digitalpartner.houseagent.property.domain.House;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies availability changes that originate in lease-service.
 *
 * <p>This is the write side of "only houses that are not rented get listed". An agent
 * can no longer make a house occupied by hand; occupancy is now derived from whether a
 * lease exists, and lease-service is the only thing that can say so.
 *
 * <p>Every method is idempotent - it sets a status rather than toggling one - because
 * event delivery is at least once and the same event will arrive twice.
 */
@ApplicationScoped
public class HouseAvailability {

    private static final Logger LOG = Logger.getLogger(HouseAvailability.class);

    @Inject
    ListingProjector listings;

    /**
     * @param houseId the house a lease was signed against
     */
    @Transactional
    public void markReserved(UUID houseId) {
        apply(houseId, AvailabilityStatus.RESERVED, "lease signed");
    }

    @Transactional
    public void markOccupied(UUID houseId) {
        apply(houseId, AvailabilityStatus.OCCUPIED, "renter moved in");
    }

    /**
     * Returns the house to the market.
     *
     * <p>Note that {@code published} is left untouched. If the agent had advertised
     * this house before it was let, it goes back on the marketplace automatically; if
     * they had not, it stays private. Re-publishing on the agency's behalf would
     * advertise houses they had deliberately taken down.
     */
    @Transactional
    public void markAvailable(UUID houseId) {
        apply(houseId, AvailabilityStatus.AVAILABLE, "lease ended");
    }

    private void apply(UUID houseId, AvailabilityStatus status, String because) {
        House house = House.findById(houseId);
        if (house == null) {
            // The tenant must be established by the caller. A null here means either a
            // genuinely unknown house or - the bug worth catching - an event processed
            // without its agency, where the filter hid a house that does exist.
            LOG.warnf("Ignoring '%s' for unknown house %s in the current agency", because, houseId);
            return;
        }
        if (house.status == status) {
            return;
        }
        house.status = status;
        house.updatedAt = Instant.now();
        listings.sync(house);
        LOG.debugf("House %s -> %s (%s)", houseId, status, because);
    }
}
