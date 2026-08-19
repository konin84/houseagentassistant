package com.digitalpartner.houseagent.payment.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a renter owes and has paid, across every agency they rent from.
 *
 * <p>This is the isolation rule the README calls "renter's own payment history", and
 * it needs its own table for the same reason the landlord's portfolio does: a renter
 * is a platform-wide principal whose token carries no {@code agency_id}. Querying
 * {@link Invoice} as a renter would hit the {@code @TenantId} filter, resolve to the
 * no-agency sentinel and correctly return nothing.
 *
 * <p>So there is no discriminator here, and {@code WHERE renter_id = :partyId} - with
 * the id taken from the validated token, never from a query parameter - is the entire
 * access control. See {@code RenterLedger}.
 */
@Entity
@Table(name = "renter_invoice_view")
public class RenterInvoiceView extends PanacheEntityBase {

    @Id
    @Column(name = "invoice_id", nullable = false, updatable = false)
    public UUID invoiceId;

    @Column(name = "renter_id", nullable = false)
    public UUID renterId;

    /** Shown so a renter knows which agency to contact. Data, not a filter. */
    @Column(name = "agency_id", nullable = false, length = 64)
    public String agencyId;

    @Column(name = "lease_id", nullable = false)
    public UUID leaseId;

    @Column(name = "house_reference", length = 200)
    public String houseReference;

    @Column(name = "period_start", nullable = false)
    public LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    public LocalDate periodEnd;

    @Column(name = "due_date", nullable = false)
    public LocalDate dueDate;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    public BigDecimal amountPaid;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public InvoiceStatus status;

    /** Rebuilds this row from the invoice it mirrors. Safe to call repeatedly. */
    public RenterInvoiceView copyFrom(Invoice invoice) {
        this.invoiceId = invoice.id;
        this.renterId = invoice.renterId;
        this.agencyId = invoice.agencyId;
        this.leaseId = invoice.leaseId;
        this.houseReference = invoice.houseReference;
        this.periodStart = invoice.periodStart;
        this.periodEnd = invoice.periodEnd;
        this.dueDate = invoice.dueDate;
        this.amount = invoice.amount;
        this.amountPaid = invoice.amountPaid;
        this.currency = invoice.currency;
        this.status = invoice.status;
        return this;
    }
}
