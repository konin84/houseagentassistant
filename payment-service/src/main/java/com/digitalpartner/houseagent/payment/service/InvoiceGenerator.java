package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.payment.domain.BillingStatus;
import com.digitalpartner.houseagent.payment.domain.Invoice;
import com.digitalpartner.houseagent.payment.domain.LeaseBilling;
import com.digitalpartner.houseagent.payment.domain.RenterInvoiceView;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Turns a lease's payment modality into the invoices that implement it.
 *
 * <h2>Why this re-derives the whole schedule every run</h2>
 *
 * The obvious design generates the next invoice when the previous one is paid, but
 * that chain breaks permanently the first time a run is missed: nothing ever notices
 * the gap. This job instead recomputes what <em>should</em> exist for every active
 * lease between its start and a horizon a few weeks out, and inserts whatever is
 * absent.
 *
 * <p>That is only safe because the schedule is deterministic and
 * {@code uq_invoice_lease_period} refuses a second invoice for the same period. Running
 * it twice, restarting mid-run, or replaying a LeaseSigned event all converge on the
 * same set of rows.
 *
 * <p>Invoices are raised ahead of their due date rather than on it, so a renter can see
 * and pay next month's rent early, and so a missed nightly run does not mean missed
 * rent.
 */
@ApplicationScoped
public class InvoiceGenerator {

    private static final Logger LOG = Logger.getLogger(InvoiceGenerator.class);

    /**
     * Stops a corrupt lease - one whose dates make the loop never advance - from
     * generating rows forever. 600 monthly periods is fifty years.
     */
    private static final int MAX_PERIODS_PER_LEASE = 600;

    @Inject
    AgencyScan agencies;

    /** How far ahead invoices are raised. */
    @ConfigProperty(name = "app.invoicing.horizon-days", defaultValue = "45")
    int horizonDays;

    @Scheduled(
            every = "${app.invoicing.interval:1h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledGeneration() {
        int created = generateDue();
        if (created > 0) {
            LOG.infof("Raised %d invoice(s)", created);
        }
    }

    /**
     * Raises every invoice now due within the horizon, returning how many were new.
     *
     * <p>Public and separate from the schedule so tests can drive it explicitly rather
     * than waiting on a timer.
     */
    public int generateDue() {
        return generateThrough(LocalDate.now().plusDays(horizonDays));
    }

    /** As {@link #generateDue()}, with an explicit horizon. Lets tests bill the future. */
    public int generateThrough(LocalDate horizon) {
        // One request context for the whole sweep, so every per-agency tenant switch
        // inside it is actually consulted. See AgencyScan.inRequestContext.
        return AgencyScan.inRequestContext(() -> {
            List<UUID> leases = activeLeaseIds();
            int created = 0;
            for (UUID leaseId : leases) {
                created += generateForLease(leaseId, horizon);
            }
            return created;
        });
    }

    private List<UUID> activeLeaseIds() {
        return AgencyScan.withoutTenant(() -> QuarkusTransaction.requiringNew().call(() ->
                LeaseBilling.<LeaseBilling>find("billingStatus", BillingStatus.ACTIVE)
                        .list()
                        .stream()
                        .map(b -> b.leaseId)
                        .toList()));
    }

    /**
     * One lease, in its own transaction and its own agency.
     *
     * <p>Per-lease rather than one transaction for the sweep: a single malformed lease
     * must not roll back everybody else's invoices, and the tenant has to be
     * re-established for each one anyway.
     */
    private int generateForLease(UUID leaseId, LocalDate horizon) {
        String agencyId = AgencyScan.withoutTenant(() -> QuarkusTransaction.requiringNew().call(() -> {
            LeaseBilling billing = LeaseBilling.findById(leaseId);
            return billing == null ? null : billing.agencyId;
        }));
        if (agencyId == null) {
            return 0;
        }

        try {
            return AgencyContext.callWith(agencyId, () ->
                    QuarkusTransaction.requiringNew().call(() -> raiseMissing(leaseId, horizon)));
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not raise invoices for lease %s; other leases continue", leaseId);
            return 0;
        }
    }

    private int raiseMissing(UUID leaseId, LocalDate horizon) {
        LeaseBilling billing = LeaseBilling.findById(leaseId);
        if (billing == null || billing.billingStatus != BillingStatus.ACTIVE) {
            return 0;
        }

        int months = billing.monthsPerPeriod();
        LocalDate dueDate = billing.firstDueDate();
        int created = 0;

        for (int period = 0; period < MAX_PERIODS_PER_LEASE && !dueDate.isAfter(horizon); period++) {
            LocalDate periodEnd = dueDate.plusMonths(months);

            // A period that begins on or after the lease ends is not owed at all. Note
            // this uses the agreed end date, so ending a lease early cancels future
            // invoices through the consumer rather than here.
            if (billing.endDate != null && !dueDate.isBefore(billing.endDate)) {
                break;
            }

            if (Invoice.count("leaseId = ?1 and periodStart = ?2", leaseId, dueDate) == 0) {
                raise(billing, dueDate, periodEnd);
                created++;
            }
            dueDate = dueDate.plusMonths(months);
        }
        return created;
    }

    private void raise(LeaseBilling billing, LocalDate periodStart, LocalDate periodEnd) {
        Invoice invoice = new Invoice();
        invoice.agencyId = billing.agencyId;
        invoice.leaseId = billing.leaseId;
        invoice.renterId = billing.renterId;
        invoice.landlordId = billing.landlordId;
        invoice.houseReference = billing.houseReference;
        invoice.periodStart = periodStart;
        invoice.periodEnd = periodEnd;
        // Rent falls due at the start of the period it covers, which is what
        // dueDayOfMonth means on the lease.
        invoice.dueDate = periodStart;
        invoice.amount = billing.rentAmount;
        invoice.currency = billing.currency;
        invoice.persist();

        // The renter's own copy, written in the same transaction so a renter can never
        // see an invoice the agency does not have, or miss one it does.
        new RenterInvoiceView().copyFrom(invoice).persist();

        invoice.updatedAt = Instant.now();
    }
}
