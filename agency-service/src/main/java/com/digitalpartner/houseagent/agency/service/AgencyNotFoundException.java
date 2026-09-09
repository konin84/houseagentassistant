package com.digitalpartner.houseagent.agency.service;

/** No such agency. */
public class AgencyNotFoundException extends RuntimeException {

    public AgencyNotFoundException(String agencyId) {
        super("No agency '" + agencyId + "'");
    }
}
