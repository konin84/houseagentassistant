package com.digitalpartner.houseagent.payment.domain;

/** Whether the agency has yet remitted a landlord's share. */
public enum PayoutStatus {

    /** Owed to the landlord, not yet sent. */
    PENDING,

    SETTLED
}
