package com.digitalpartner.houseagent.agency.domain;

/**
 * What an agency is entitled to.
 *
 * <p>The limit is on houses in the catalogue rather than houses advertised. An agency
 * on the free plan may hold five properties, whether or not any of them are currently on
 * the marketplace - which is both the easier thing to explain to a customer and the
 * easier thing to enforce, because there is exactly one moment where it applies.
 */
public enum SubscriptionPlan {

    /** What every agency starts on, and what an unknown agency is assumed to be. */
    FREE(5),

    STARTER(25),

    PROFESSIONAL(100),

    /**
     * No ceiling.
     *
     * <p>Null rather than a very large number, so that "unlimited" can never be
     * mistaken for a limit somebody chose, and so no arithmetic quietly applies to it.
     */
    ENTERPRISE(null);

    private final Integer maxHouses;

    SubscriptionPlan(Integer maxHouses) {
        this.maxHouses = maxHouses;
    }

    /** The ceiling, or null when there is none. */
    public Integer maxHouses() {
        return maxHouses;
    }

    public boolean isUnlimited() {
        return maxHouses == null;
    }
}
