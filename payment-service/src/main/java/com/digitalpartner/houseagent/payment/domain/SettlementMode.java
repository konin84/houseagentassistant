package com.digitalpartner.houseagent.payment.domain;

/**
 * How rent money actually moves for a given agency.
 *
 * <p>Both models exist in this market and the platform cannot pick one for everybody,
 * so the choice is per agency and everything downstream branches on it exactly once,
 * in {@code SettlementPolicy}.
 */
public enum SettlementMode {

    /**
     * The renter pays the agency, which keeps a commission and owes the landlord the
     * rest. The platform is briefly holding someone else's money, so every settled
     * payment produces a {@link Payout} recording what is owed to whom.
     */
    PLATFORM_COLLECTS,

    /**
     * The renter pays the landlord directly and the platform only records that it
     * happened. No commission, no payout, nothing held - the landlord is notified and
     * the invoice is marked paid, and that is the whole of it.
     */
    DIRECT_TO_LANDLORD;

    /**
     * The mode assumed for an agency that has never configured one.
     *
     * <p>Defaulting to the mode where the platform holds no money is the safe
     * direction to be wrong in: an agency that has not opted in to collection cannot
     * accidentally accrue payouts it does not know about.
     */
    public static final SettlementMode DEFAULT = DIRECT_TO_LANDLORD;

    public boolean platformHoldsTheMoney() {
        return this == PLATFORM_COLLECTS;
    }
}
