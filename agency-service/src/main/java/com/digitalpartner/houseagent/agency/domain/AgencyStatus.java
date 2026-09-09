package com.digitalpartner.houseagent.agency.domain;

/** Whether an agency may still operate. */
public enum AgencyStatus {

    ACTIVE,

    /**
     * Stopped, but not deleted.
     *
     * <p>Their houses, leases and invoices all still exist and still have to be
     * explicable. Deleting an agency would orphan every one of them.
     */
    SUSPENDED
}
