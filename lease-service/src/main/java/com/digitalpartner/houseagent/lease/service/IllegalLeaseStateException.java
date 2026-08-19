package com.digitalpartner.houseagent.lease.service;

/** Raised when a lifecycle transition is not legal from the lease's current state. */
public class IllegalLeaseStateException extends RuntimeException {

    public IllegalLeaseStateException(String message) {
        super(message);
    }
}
