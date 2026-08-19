package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.RenterInvoiceResponse;
import com.digitalpartner.houseagent.payment.domain.InvoiceStatus;
import com.digitalpartner.houseagent.payment.domain.RenterInvoiceView;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A renter's own rent: what they owe, what they have paid, across every agency.
 *
 * <h2>The only thing isolating one renter from another</h2>
 *
 * {@code renter_invoice_view} carries no {@code @TenantId}, because renters are not
 * agency-scoped - the same person may rent from agency A this year and agency B next,
 * and their token names neither. Nothing in Hibernate will narrow these queries. The
 * {@code renterId = :renterId} predicate below <em>is</em> the access control, and the
 * id comes from the validated token, never from the request.
 *
 * <p>As with {@code LandlordPortfolio} in lease-service, this is deliberately the whole
 * of a small class rather than a few methods among many, so that adding an unfiltered
 * query here looks as wrong as it is.
 */
@ApplicationScoped
public class RenterLedger {

    public static final int MAX_PAGE_SIZE = 100;

    @Transactional
    public List<RenterInvoiceResponse> invoicesOf(UUID renterId, boolean unpaidOnly,
                                                  int page, int size) {
        String query = unpaidOnly
                ? "renterId = :renterId and status in ('DUE', 'PARTIALLY_PAID')"
                : "renterId = :renterId";

        return RenterInvoiceView.<RenterInvoiceView>find(
                        query,
                        Sort.by("dueDate").descending(),
                        Parameters.with("renterId", renterId))
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(RenterInvoiceResponse::from)
                .toList();
    }

    @Transactional
    public long countInvoicesOf(UUID renterId, boolean unpaidOnly) {
        return unpaidOnly
                ? RenterInvoiceView.count(
                        "renterId = :renterId and status in ('DUE', 'PARTIALLY_PAID')",
                        Parameters.with("renterId", renterId))
                : RenterInvoiceView.count("renterId = :renterId",
                        Parameters.with("renterId", renterId));
    }

    /**
     * One invoice belonging to this renter.
     *
     * <p>The renter id is part of the lookup rather than checked afterwards, so someone
     * else's invoice is simply not found.
     */
    @Transactional
    public RenterInvoiceResponse invoiceOf(UUID renterId, UUID invoiceId) {
        RenterInvoiceView view = RenterInvoiceView.<RenterInvoiceView>find(
                        "invoiceId = :invoiceId and renterId = :renterId",
                        Parameters.with("invoiceId", invoiceId).and("renterId", renterId))
                .firstResult();
        if (view == null) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        return RenterInvoiceResponse.from(view);
    }

    /** What this renter currently owes, so a client can show one number. */
    @Transactional
    public java.math.BigDecimal totalOutstanding(UUID renterId) {
        return RenterInvoiceView.<RenterInvoiceView>find(
                        "renterId = :renterId and status in ('DUE', 'PARTIALLY_PAID')",
                        Parameters.with("renterId", renterId))
                .list()
                .stream()
                .map(v -> v.amount.subtract(v.amountPaid))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /** Exposed so the status filter above and the DTO cannot drift apart. */
    public static boolean isUnpaid(InvoiceStatus status) {
        return status.isPayable();
    }
}
