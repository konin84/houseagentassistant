package com.digitalpartner.houseagent.agency.identity;

/**
 * A person to be created.
 *
 * <p>Note that {@code agencyId} and {@code partyId} are set by the service rather than
 * taken from a request - see {@code StaffDirectory} and {@code PartyDirectory}. By the
 * time a value reaches this record it has already been decided, not accepted.
 */
public record NewUser(
        String email,
        String firstName,
        String lastName,
        String role,
        String agencyId,
        String partyId,
        String temporaryPassword) {
}
