package com.digitalpartner.houseagent.property.domain;

/**
 * Lifecycle of a house with respect to letting.
 * <p>
 * property-service does not decide these transitions on its own once lease-service
 * exists - it moves between RESERVED/OCCUPIED/AVAILABLE in reaction to lease events.
 * Only AVAILABLE houses may appear on the public marketplace.
 */
public enum AvailabilityStatus {

    /** No active or pending lease. Eligible for the marketplace. */
    AVAILABLE,

    /** A lease is signed but the renter has not moved in yet. */
    RESERVED,

    /** Currently let. */
    OCCUPIED,

    /** Withdrawn by the agency or landlord: renovation, dispute, off-market. */
    UNAVAILABLE;

    public boolean isListable() {
        return this == AVAILABLE;
    }
}
