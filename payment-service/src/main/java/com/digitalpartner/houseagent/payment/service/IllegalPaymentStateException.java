package com.digitalpartner.houseagent.payment.service;

/**
 * A payment or invoice was asked to do something its current state does not allow -
 * settling twice, paying a cancelled invoice, or paying more than is outstanding.
 */
public class IllegalPaymentStateException extends RuntimeException {

    public IllegalPaymentStateException(String message) {
        super(message);
    }
}
