package com.digitalpartner.houseagent.payment.domain;

/**
 * Where a payment stands with its provider.
 *
 * <p>PENDING exists because a mobile money push is not instant: the renter initiates
 * it, approves it on their handset, and the provider confirms some seconds or minutes
 * later. Treating initiation as payment would mark rent received that never arrived.
 */
public enum PaymentStatus {

    /** Initiated with a provider, not yet confirmed. Money has not moved. */
    PENDING,

    /** Confirmed. This is the only status that reduces what a renter owes. */
    SETTLED,

    FAILED
}
