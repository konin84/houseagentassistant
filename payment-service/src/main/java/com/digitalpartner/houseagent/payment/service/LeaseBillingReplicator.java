package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.common.events.LeaseEvents.PaymentCadence;
import com.digitalpartner.houseagent.payment.domain.BillingStatus;
import com.digitalpartner.houseagent.payment.domain.Invoice;
import com.digitalpartner.houseagent.payment.domain.InvoiceStatus;
import com.digitalpartner.houseagent.payment.domain.LeaseBilling;
import com.digitalpartner.houseagent.payment.domain.RenterInvoiceView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Maintains this service's replica of the lease terms it bills against.
 *
 * <h2>Why this is a bean of its own rather than methods on the consumer</h2>
 *
 * {@code @Transactional} is a CDI interceptor, and interceptors do not apply when an
 * object calls its own method - the call never passes through the proxy. A consumer
 * that invoked its own transactional method would persist outside a transaction and
 * fail at runtime, having compiled perfectly. Keeping the writes on a separate injected
 * bean is what makes the annotation mean something.
 */
@ApplicationScoped
public class LeaseBillingReplicator {

    private static final Logger LOG = Logger.getLogger(LeaseBillingReplicator.class);

    /**
     * Copies the lease terms this service bills against.
     *
     * <p>Rewrites the row rather than skipping when it already exists, so a corrected
     * lease that is re-signed does not leave stale terms behind. Invoices already
     * raised are untouched - see {@link InvoiceGenerator}.
     */
    @Transactional
    public void startBilling(UUID leaseId, String agencyId, JsonNode payload) {
        LeaseBilling billing = LeaseBilling.findById(leaseId);
        boolean isNew = billing == null;
        if (isNew) {
            billing = new LeaseBilling();
            billing.leaseId = leaseId;
        }

        billing.agencyId = agencyId;
        billing.houseId = uuidOf(payload, "houseId");
        billing.houseReference = textOf(payload, "houseReference");
        billing.renterId = uuidOf(payload, "renterId");
        billing.renterName = textOf(payload, "renterName");
        billing.landlordId = uuidOf(payload, "landlordId");
        billing.rentAmount = decimalOf(payload, "rentAmount");
        billing.currency = textOf(payload, "currency");
        billing.cadence = cadenceOf(payload);
        billing.dueDayOfMonth = payload.path("dueDayOfMonth").asInt(1);
        billing.startDate = dateOf(payload, "startDate");
        billing.endDate = dateOf(payload, "endDate");
        billing.billingStatus = BillingStatus.ACTIVE;
        billing.updatedAt = Instant.now();

        if (billing.houseId == null || billing.renterId == null || billing.landlordId == null
                || billing.rentAmount == null || billing.currency == null
                || billing.startDate == null) {
            // Fail loudly rather than persisting a lease that cannot be billed. A
            // half-populated row would silently produce no invoices at all, which is
            // indistinguishable from a lease with nothing owed.
            throw new IllegalStateException(
                    "LeaseSigned for " + leaseId + " is missing fields required to bill it");
        }

        if (isNew) {
            billing.persist();
        }
    }

    /**
     * Stops billing and cancels invoices for periods that will never happen.
     *
     * <p>Only invoices that are wholly unpaid and start in the future are cancelled. One
     * already part-paid, or covering a period the renter has lived through, remains
     * owed - moving out early does not refund rent already due.
     */
    @Transactional
    public void stopBilling(UUID leaseId) {
        LeaseBilling billing = LeaseBilling.findById(leaseId);
        if (billing == null) {
            // The lease was never signed as far as this service knows: nothing to bill
            // and nothing to cancel.
            LOG.debugf("LeaseEnded for unknown lease %s", leaseId);
            return;
        }

        billing.billingStatus = BillingStatus.ENDED;
        billing.updatedAt = Instant.now();

        List<Invoice> future = Invoice.list(
                "leaseId = ?1 and status = ?2 and periodStart > ?3",
                leaseId, InvoiceStatus.DUE, LocalDate.now());

        for (Invoice invoice : future) {
            invoice.status = InvoiceStatus.CANCELLED;
            invoice.updatedAt = Instant.now();
            RenterInvoiceView view = RenterInvoiceView.findById(invoice.id);
            if (view != null) {
                view.copyFrom(invoice);
            }
        }
    }

    // ---------------------------------------------------------------- parsing

    private static String textOf(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID uuidOf(JsonNode payload, String field) {
        String text = textOf(payload, field);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads the decimal straight off the node rather than via its text form.
     *
     * <p>Jackson strips trailing zeros when parsing into a BigDecimal, so
     * {@code 150000.00} renders as {@code 1.5E+5}. Parsing that back yields the right
     * value but a negative scale, and going through the string at all is a needless
     * place for a rent amount to be reinterpreted.
     */
    private static BigDecimal decimalOf(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue().setScale(2, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(node.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static LocalDate dateOf(JsonNode payload, String field) {
        String text = textOf(payload, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PaymentCadence cadenceOf(JsonNode payload) {
        String text = textOf(payload, "cadence");
        if (text == null) {
            return PaymentCadence.MONTHLY;
        }
        try {
            return PaymentCadence.valueOf(text);
        } catch (IllegalArgumentException e) {
            // An unknown cadence from a newer lease-service. Monthly is the safe
            // fallback: it invoices more often than any other, so nothing goes unbilled.
            LOG.warnf("Unknown payment cadence '%s', billing monthly", text);
            return PaymentCadence.MONTHLY;
        }
    }
}
