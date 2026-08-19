package com.digitalpartner.houseagent.lease.service;

import java.util.UUID;

/**
 * Raised when a lease id does not resolve within the caller's own agency.
 *
 * <p>As in property-service, this is deliberately indistinguishable from "does not
 * exist": a 403 would confirm that another agency holds that lease.
 */
public class LeaseNotFoundException extends RuntimeException {

    public LeaseNotFoundException(UUID id) {
        super("Lease " + id + " not found");
    }
}
