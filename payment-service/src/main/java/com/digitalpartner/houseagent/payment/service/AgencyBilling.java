package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.InvoiceResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.PaymentResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.PayoutResponse;
import com.digitalpartner.houseagent.payment.domain.Invoice;
import com.digitalpartner.houseagent.payment.domain.InvoiceStatus;
import com.digitalpartner.houseagent.payment.domain.Payment;
import com.digitalpartner.houseagent.payment.domain.Payout;
import com.digitalpartner.houseagent.payment.domain.PayoutStatus;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What agency staff can see and do with their own agency's money.
 *
 * <p>Unlike {@link RenterLedger} and {@link LandlordEarnings}, nothing here carries an
 * explicit isolation predicate - and it should not. {@code Invoice}, {@code Payment}
 * and {@code Payout} all carry {@code @TenantId}, so Hibernate narrows every query
 * below to the caller's agency whether or not it remembers to say so. Adding a manual
 * {@code agencyId} filter here would be the wrong instinct: it would suggest the filter
 * is optional.
 */
@ApplicationScoped
public class AgencyBilling {

    public static final int MAX_PAGE_SIZE = 100;

    // ---------------------------------------------------------------- invoices

    @Transactional
    public List<InvoiceResponse> invoices(boolean unpaidOnly, int page, int size) {
        String query = unpaidOnly ? "status in ('DUE', 'PARTIALLY_PAID')" : null;

        var find = query == null
                ? Invoice.<Invoice>findAll(Sort.by("dueDate").descending())
                : Invoice.<Invoice>find(query, Sort.by("dueDate").descending());

        return find.page(Page.of(page, size)).list().stream()
                .map(InvoiceResponse::from)
                .toList();
    }

    @Transactional
    public long countInvoices(boolean unpaidOnly) {
        return unpaidOnly ? Invoice.count("status in ('DUE', 'PARTIALLY_PAID')") : Invoice.count();
    }

    @Transactional
    public InvoiceResponse invoice(UUID id) {
        Invoice invoice = Invoice.findById(id);
        if (invoice == null) {
            throw new InvoiceNotFoundException(id);
        }
        return InvoiceResponse.from(invoice);
    }

    /** Everything late right now, oldest first - the agency's chase list. */
    @Transactional
    public List<InvoiceResponse> overdueInvoices(int page, int size) {
        return Invoice.<Invoice>find(
                        "status in :payable and dueDate < :today",
                        Sort.by("dueDate").ascending(),
                        Parameters.with("payable",
                                        List.of(InvoiceStatus.DUE, InvoiceStatus.PARTIALLY_PAID))
                                .and("today", LocalDate.now()))
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(InvoiceResponse::from)
                .toList();
    }

    // ---------------------------------------------------------------- payments

    @Transactional
    public List<PaymentResponse> payments(int page, int size) {
        return Payment.<Payment>findAll(Sort.by("initiatedAt").descending())
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional
    public long countPayments() {
        return Payment.count();
    }

    // ----------------------------------------------------------------- payouts

    @Transactional
    public List<PayoutResponse> payouts(boolean pendingOnly, int page, int size) {
        var find = pendingOnly
                ? Payout.<Payout>find("status", Sort.by("createdAt").ascending(), PayoutStatus.PENDING)
                : Payout.<Payout>findAll(Sort.by("createdAt").descending());

        return find.page(Page.of(page, size)).list().stream()
                .map(PayoutResponse::from)
                .toList();
    }

    @Transactional
    public long countPayouts(boolean pendingOnly) {
        return pendingOnly ? Payout.count("status", PayoutStatus.PENDING) : Payout.count();
    }

    /**
     * Marks a payout as remitted to the landlord.
     *
     * <p>Records that a transfer happened elsewhere; it does not move money itself.
     * Actually paying out means a bank or mobile money disbursement, which is a
     * provider integration rather than a database update.
     */
    @Transactional
    public PayoutResponse markPayoutSettled(UUID id) {
        Payout payout = Payout.findById(id);
        if (payout == null) {
            throw new PaymentNotFoundException(id);
        }
        if (payout.status == PayoutStatus.SETTLED) {
            return PayoutResponse.from(payout);
        }
        payout.status = PayoutStatus.SETTLED;
        payout.settledAt = Instant.now();
        return PayoutResponse.from(payout);
    }
}
