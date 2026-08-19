package com.digitalpartner.houseagent.payment.service;

/** A settlement policy that cannot mean anything, such as commission with no collection. */
public class IllegalSettlementConfigException extends RuntimeException {

    public IllegalSettlementConfigException(String message) {
        super(message);
    }
}
