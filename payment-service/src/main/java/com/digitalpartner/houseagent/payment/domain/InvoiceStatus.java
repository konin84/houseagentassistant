package com.digitalpartner.houseagent.payment.domain;

/**
 * Where an invoice stands.
 *
 * <p>Note the absence of an OVERDUE value. Being late is a function of the due date
 * and the clock, not a state someone has to remember to transition into - a nightly
 * job that failed to run would otherwise leave invoices looking current. Lateness is
 * derived; only {@code overdueNotifiedAt} is stored, and only to avoid telling a
 * landlord about the same late invoice twice.
 */
public enum InvoiceStatus {

    DUE,
    PARTIALLY_PAID,
    PAID,

    /** Raised in error, or the lease ended before the period began. */
    CANCELLED;

    /** True while this invoice can still take money. */
    public boolean isPayable() {
        return this == DUE || this == PARTIALLY_PAID;
    }
}
