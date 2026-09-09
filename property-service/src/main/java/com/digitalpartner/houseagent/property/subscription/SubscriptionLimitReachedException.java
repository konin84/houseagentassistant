package com.digitalpartner.houseagent.property.subscription;

/**
 * The agency is at the ceiling its plan allows.
 *
 * <p>Carries the numbers rather than a bare refusal. "You have 5 of 5 houses on the
 * FREE plan" tells an agent what to do next; "forbidden" sends them to support.
 */
public class SubscriptionLimitReachedException extends RuntimeException {

    private final String plan;
    private final int maxHouses;
    private final long held;

    public SubscriptionLimitReachedException(String plan, int maxHouses, long held) {
        super("The " + plan + " plan allows " + maxHouses + " houses and this agency has "
                + held + ". Upgrade the plan, or remove a house, to add another.");
        this.plan = plan;
        this.maxHouses = maxHouses;
        this.held = held;
    }

    public String plan() {
        return plan;
    }

    public int maxHouses() {
        return maxHouses;
    }

    public long held() {
        return held;
    }
}
