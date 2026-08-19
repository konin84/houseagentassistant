package com.digitalpartner.houseagent.lease.domain;

import com.digitalpartner.houseagent.common.events.LeaseEvents.PaymentCadence;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How and when rent is paid on a lease.
 *
 * <p>This is the "modality of payment" a landlord needs to see, and it is also what
 * payment-service will derive invoice amounts from. Keeping it on the lease rather
 * than on the house matters: two renters in identical houses can be on different
 * terms, and a house's advertised price is not necessarily what was agreed.
 */
@Embeddable
public class PaymentModality {

    /** Agreed rent per cadence period - not necessarily the house's listed price. */
    @Column(name = "rent_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal rentAmount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false, length = 20)
    public PaymentCadence cadence;

    /**
     * Day of the month rent falls due, 1-28.
     * <p>Capped at 28 deliberately: a lease due on the 31st has no due date in
     * February, and every scheme for fixing that afterwards is worse than not
     * allowing it.
     */
    @Column(name = "due_day_of_month", nullable = false)
    public int dueDayOfMonth;

    /** Security deposit held. Null when none was taken. */
    @Column(name = "deposit_amount", precision = 14, scale = 2)
    public BigDecimal depositAmount;

    public PaymentModality() {
    }

    public PaymentModality(BigDecimal rentAmount, String currency, PaymentCadence cadence,
                           int dueDayOfMonth, BigDecimal depositAmount) {
        this.rentAmount = rentAmount;
        this.currency = currency;
        this.cadence = cadence;
        this.dueDayOfMonth = dueDayOfMonth;
        this.depositAmount = depositAmount;
    }

    /** Number of months between due dates, used by invoicing and reminders. */
    public int monthsBetweenDueDates() {
        return switch (cadence) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case BIANNUAL -> 6;
            case ANNUAL -> 12;
        };
    }

    /** The first date rent falls due on or after the lease start. */
    public LocalDate firstDueDate(LocalDate startDate) {
        LocalDate candidate = startDate.withDayOfMonth(dueDayOfMonth);
        return candidate.isBefore(startDate) ? candidate.plusMonths(1) : candidate;
    }
}
