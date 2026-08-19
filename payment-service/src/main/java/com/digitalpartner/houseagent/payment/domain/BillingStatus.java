package com.digitalpartner.houseagent.payment.domain;

/** Whether a lease is still generating invoices. */
public enum BillingStatus {

    ACTIVE,

    /**
     * The lease ended. Kept rather than deleted: invoices already raised against it
     * must stay explicable, and a renter's history must not vanish when they move out.
     */
    ENDED
}
