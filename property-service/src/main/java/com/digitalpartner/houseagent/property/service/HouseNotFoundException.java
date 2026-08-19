package com.digitalpartner.houseagent.property.service;

import java.util.UUID;

/**
 * Thrown when a house id does not resolve within the caller's own agency.
 *
 * <p>Deliberately indistinguishable from "does not exist at all". Telling a caller
 * that a house exists but belongs to someone else leaks the shape of a competitor's
 * portfolio, so both cases produce the same 404.
 */
public class HouseNotFoundException extends RuntimeException {

    public HouseNotFoundException(UUID id) {
        super("House " + id + " not found");
    }
}
