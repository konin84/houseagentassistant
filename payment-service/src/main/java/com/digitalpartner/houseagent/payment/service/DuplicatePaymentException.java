package com.digitalpartner.houseagent.payment.service;

/**
 * The provider's reference has already been recorded.
 *
 * <p>Not an error in the usual sense - it is the expected outcome when a provider
 * retries a callback it has already delivered, which they all do. The caller is told
 * which payment already represents that transaction so it can stop retrying.
 */
public class DuplicatePaymentException extends RuntimeException {

    private final java.util.UUID existingPaymentId;

    public DuplicatePaymentException(String providerReference, java.util.UUID existingPaymentId) {
        super("Payment for provider reference '" + providerReference + "' is already recorded");
        this.existingPaymentId = existingPaymentId;
    }

    public java.util.UUID existingPaymentId() {
        return existingPaymentId;
    }
}
