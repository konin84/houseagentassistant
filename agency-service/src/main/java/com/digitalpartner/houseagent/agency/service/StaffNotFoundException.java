package com.digitalpartner.houseagent.agency.service;

/**
 * Also thrown when the person exists but belongs to another agency.
 *
 * <p>Deliberately indistinguishable, for the same reason a foreign house is a 404
 * everywhere else on this platform: a different answer would confirm that the id
 * belongs to somebody.
 */
public class StaffNotFoundException extends RuntimeException {

    public StaffNotFoundException(String userId) {
        super("No staff member " + userId + " in this agency");
    }
}
