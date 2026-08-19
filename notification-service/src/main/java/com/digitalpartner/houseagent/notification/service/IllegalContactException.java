package com.digitalpartner.houseagent.notification.service;

/** A contact that could never be reached, such as one with no address. */
public class IllegalContactException extends RuntimeException {

    public IllegalContactException(String message) {
        super(message);
    }
}
