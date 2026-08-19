package com.digitalpartner.houseagent.lease.domain;

/**
 * Lifecycle of a lease.
 *
 * <p>{@link #PENDING_MOVE_IN} and {@link #ACTIVE} are the two states that make a house
 * unavailable, and they are exactly the two covered by the overlapping-lease exclusion
 * constraint. Changing that pair here means changing the constraint in a migration -
 * the two definitions must stay in step or double-letting becomes possible again.
 */
public enum LeaseStatus {

    /** Signed, but the renter has not moved in yet. The house is already spoken for. */
    PENDING_MOVE_IN,

    /** The renter is in occupation and rent is due on the agreed cadence. */
    ACTIVE,

    /** Ran to term or was terminated early. The house returns to the market. */
    ENDED,

    /** Called off before move-in. The house returns to the market. */
    CANCELLED;

    /** True while this lease occupies the house and blocks any other lease on it. */
    public boolean occupiesHouse() {
        return this == PENDING_MOVE_IN || this == ACTIVE;
    }
}
