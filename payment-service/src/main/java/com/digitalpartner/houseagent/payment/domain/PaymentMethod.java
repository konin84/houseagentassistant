package com.digitalpartner.houseagent.payment.domain;

/**
 * How the money was handed over.
 *
 * <p>MOBILE_MONEY leads the list because it is how rent is actually paid in this
 * market - Wave, Orange Money and MTN MoMo rather than cards. Modelling card-first
 * would have been importing an assumption from a different continent.
 */
public enum PaymentMethod {

    MOBILE_MONEY,

    /** Taken at the agency's office. Has no provider reference to be idempotent on. */
    CASH,

    BANK_TRANSFER,

    CARD;

    /**
     * Whether a payment by this method must quote a provider reference.
     *
     * <p>Cash has no provider and no callback, so requiring one would make it
     * impossible to record. Everything else has an external transaction that can
     * arrive twice, and the reference is what makes the second one a no-op.
     */
    public boolean requiresProviderReference() {
        return this != CASH;
    }
}
