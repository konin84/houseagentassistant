package com.digitalpartner.houseagent.payment.service;

import java.util.UUID;

/**
 * Also thrown when the invoice exists but belongs to someone else.
 *
 * <p>Deliberately indistinguishable from genuinely missing: a 403 would confirm that
 * an invoice with that id exists, which is itself something a renter is not entitled
 * to learn about another renter.
 */
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID id) {
        super("No invoice " + id);
    }
}
