package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.common.events.PaymentEvents;
import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.payment.domain.Invoice;
import com.digitalpartner.houseagent.payment.domain.InvoiceStatus;
import com.digitalpartner.houseagent.payment.domain.LeaseBilling;
import com.digitalpartner.houseagent.payment.outbox.OutboxWriter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Notices rent that has not arrived.
 *
 * <p>Lateness is derived from the due date and the clock rather than stored as a
 * status - see {@link InvoiceStatus}. This job does not change what an invoice
 * <em>is</em>; it only announces, once, that one has gone past its date, so an agency
 * can chase it and a landlord is not left assuming they have been paid.
 *
 * <p>{@code overdueNotifiedAt} is what makes "once" true. Without it, a job running
 * every six hours would email a landlord four times a day about the same unpaid rent,
 * and they would stop reading any of it.
 */
@ApplicationScoped
public class ArrearsMonitor {

    private static final Logger LOG = Logger.getLogger(ArrearsMonitor.class);

    private static final String AGGREGATE = "invoice";

    @Inject
    AgencyScan agencies;

    @Inject
    OutboxWriter outbox;

    @Scheduled(
            every = "${app.arrears.interval:6h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledSweep() {
        int announced = sweep();
        if (announced > 0) {
            LOG.infof("Announced %d newly overdue invoice(s)", announced);
        }
    }

    /**
     * Announces every invoice that has gone overdue since the last run, returning how
     * many were newly announced.
     *
     * <p>Public and separate from the schedule so tests can drive it explicitly.
     */
    public int sweep() {
        return sweepAsOf(LocalDate.now());
    }

    /** As {@link #sweep()}, with an explicit date. Lets tests age an invoice. */
    public int sweepAsOf(LocalDate today) {
        // One request context for the whole sweep, so every per-agency tenant switch
        // inside it is actually consulted. See AgencyScan.inRequestContext.
        return AgencyScan.inRequestContext(() -> {
            int announced = 0;
            for (String agencyId : agencies.agenciesWithBilling()) {
                try {
                    announced += AgencyContext.callWith(agencyId, () ->
                            QuarkusTransaction.requiringNew().call(() -> announceOverdue(today)));
                } catch (RuntimeException e) {
                    // One agency's bad data must not stop every other agency being chased.
                    LOG.errorf(e, "Arrears sweep failed for agency %s; others continue", agencyId);
                }
            }
            return announced;
        });
    }

    private int announceOverdue(LocalDate today) {
        List<Invoice> late = Invoice.<Invoice>find(
                        "status in :payable and dueDate < :today and overdueNotifiedAt is null",
                        Parameters.with("payable",
                                        List.of(InvoiceStatus.DUE, InvoiceStatus.PARTIALLY_PAID))
                                .and("today", today))
                .list();

        for (Invoice invoice : late) {
            LeaseBilling billing = LeaseBilling.findById(invoice.leaseId);

            outbox.record(AGGREGATE, invoice.id, invoice.agencyId, "RentOverdue",
                    new PaymentEvents.RentOverdue(
                            invoice.id,
                            invoice.leaseId,
                            invoice.agencyId,
                            invoice.renterId,
                            billing == null ? null : billing.renterName,
                            invoice.landlordId,
                            invoice.houseReference,
                            invoice.outstanding(),
                            invoice.currency,
                            invoice.dueDate,
                            invoice.daysOverdueOn(today),
                            Instant.now()));

            // Written in the same transaction as the event. A crash between the two
            // would otherwise either announce twice or never announce at all.
            invoice.overdueNotifiedAt = Instant.now();
        }
        return late.size();
    }
}
