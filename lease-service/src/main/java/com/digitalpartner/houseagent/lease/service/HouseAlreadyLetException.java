package com.digitalpartner.houseagent.lease.service;

import java.util.UUID;

/**
 * Raised when a lease would overlap an existing one on the same house.
 *
 * <p>This is not detected by checking first and then inserting - that loses the race
 * when two agents sign simultaneously, because both read "free" before either writes.
 * It is the database's {@code no_overlapping_active_lease} exclusion constraint
 * rejecting the second transaction, translated into a domain exception.
 */
public class HouseAlreadyLetException extends RuntimeException {

    public HouseAlreadyLetException(UUID houseId) {
        super("House " + houseId + " already has a lease covering part of that period");
    }
}
