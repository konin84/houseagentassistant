package com.digitalpartner.houseagent.payment.service;

import java.util.UUID;

/** Also thrown when the payment belongs to another agency, for the same reason. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("No payment " + id);
    }
}
