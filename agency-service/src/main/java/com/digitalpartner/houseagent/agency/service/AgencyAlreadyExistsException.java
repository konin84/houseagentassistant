package com.digitalpartner.houseagent.agency.service;

/**
 * The slug is taken.
 *
 * <p>Worth refusing loudly rather than merging into the existing record: an agency id
 * is the tenant discriminator, and two customers sharing one would mean each seeing the
 * other's houses.
 */
public class AgencyAlreadyExistsException extends RuntimeException {

    public AgencyAlreadyExistsException(String agencyId) {
        super("Agency '" + agencyId + "' already exists");
    }
}
