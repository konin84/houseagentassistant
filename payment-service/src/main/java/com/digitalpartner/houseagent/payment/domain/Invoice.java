package com.digitalpartner.houseagent.payment.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Rent owed for one period of one lease.
 *
 * <p>Derived from the lease's payment modality rather than entered by hand: the whole
 * schedule of an active lease is re-derivable at any time, which is what lets the
 * generator run repeatedly without producing duplicates.
 */
@Entity
@Table(name = "invoice")
public class Invoice extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @TenantId
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @Column(name = "lease_id", nullable = false, updatable = false)
    public UUID leaseId;

    /** Platform-wide party ids, copied so a renter's ledger needs no join. */
    @Column(name = "renter_id", nullable = false, updatable = false)
    public UUID renterId;

    @Column(name = "landlord_id", nullable = false, updatable = false)
    public UUID landlordId;

    @Column(name = "house_reference", length = 200)
    public String houseReference;

    /** Half-open, like the lease periods it mirrors: [periodStart, periodEnd). */
    @Column(name = "period_start", nullable = false, updatable = false)
    public LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    public LocalDate periodEnd;

    @Column(name = "due_date", nullable = false)
    public LocalDate dueDate;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    /** Sum of settled payments. Never touched by a payment that is only PENDING. */
    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    public BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public InvoiceStatus status = InvoiceStatus.DUE;

    /**
     * When this invoice was last announced as overdue.
     *
     * <p>Stored only so the arrears sweep tells a landlord once rather than on every
     * run. It is not part of the invoice's state - see {@link InvoiceStatus}.
     */
    @Column(name = "overdue_notified_at")
    public Instant overdueNotifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /** Two agents recording a payment against the same invoice must not both win. */
    @Version
    @Column(name = "version", nullable = false)
    public long version;

    public BigDecimal outstanding() {
        return amount.subtract(amountPaid);
    }

    /** Late is derived from the clock, never stored. */
    public boolean isOverdueOn(LocalDate today) {
        return status.isPayable() && dueDate.isBefore(today);
    }

    public int daysOverdueOn(LocalDate today) {
        return isOverdueOn(today) ? (int) ChronoUnit.DAYS.between(dueDate, today) : 0;
    }

    /**
     * Applies a settled payment, moving the invoice to its resulting status.
     *
     * <p>Comparing with {@code compareTo} rather than {@code equals}: 150000.00 and
     * 150000.0000 are the same money but not equal BigDecimals, and an invoice that
     * was fully paid must not be left looking partially paid because of trailing
     * zeroes from a provider.
     */
    public void applySettled(BigDecimal paid) {
        this.amountPaid = this.amountPaid.add(paid);
        this.status = this.amountPaid.compareTo(this.amount) >= 0
                ? InvoiceStatus.PAID
                : InvoiceStatus.PARTIALLY_PAID;
        this.updatedAt = Instant.now();
    }
}
