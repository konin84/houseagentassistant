package com.digitalpartner.houseagent.property.service;

/** Thrown when a requested transition is not legal for the house's current state. */
public class IllegalHouseStateException extends RuntimeException {

    public IllegalHouseStateException(String message) {
        super(message);
    }
}
