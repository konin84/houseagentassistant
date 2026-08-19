package com.digitalpartner.houseagent.payment.api.dto;

import com.digitalpartner.houseagent.payment.domain.AgencySettlementConfig;
import com.digitalpartner.houseagent.payment.domain.Invoice;
import com.digitalpartner.houseagent.payment.domain.InvoiceStatus;
import com.digitalpartner.houseagent.payment.domain.LandlordEarningView;
import com.digitalpartner.houseagent.payment.domain.Payment;
import com.digitalpartner.houseagent.payment.domain.PaymentMethod;
import com.digitalpartner.houseagent.payment.domain.PaymentStatus;
import com.digitalpartner.houseagent.payment.domain.Payout;
import com.digitalpartner.houseagent.payment.domain.PayoutStatus;
import com.digitalpartner.houseagent.payment.domain.RenterInvoiceView;
import com.digitalpartner.houseagent.payment.domain.SettlementMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request and response shapes for payment-service.
 *
 * <p>Kept separate from the entities, as elsewhere on the platform: serving
 * {@code Payment} directly would publish {@code agencyId} and let a client set it on
 * the way in, which in this service means paying someone else's rent from their money.
 */
public final class PaymentDtos {

    // ---------------------------------------------------------------- requests

    /** An agency recording money it has already received. Settles immediately. */
    public record RecordPaymentRequest(
            @NotNull UUID invoiceId,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
            @NotNull PaymentMethod method,
            /** Required for everything but cash - it is the idempotency key. */
            @Size(max = 200) String providerReference) {
    }

    /** A renter starting a payment. Lands PENDING until the provider confirms. */
    public record InitiatePaymentRequest(
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 200) String providerReference) {
    }

    public record ConfirmSettlementRequest(@Size(max = 200) String providerReference) {
    }

    public record FailPaymentRequest(@Size(max = 200) String reason) {
    }

    /**
     * @param commissionBps basis points, so 750 is 7.5%. Must be zero unless the mode
     *                      is PLATFORM_COLLECTS - the platform cannot take a cut of
     *                      money it never holds.
     */
    public record SettlementConfigRequest(
            @NotNull SettlementMode mode,
            @Min(0) @Max(10_000) int commissionBps) {
    }

    // --------------------------------------------------------------- responses

    public record SettlementConfigResponse(
            String agencyId,
            SettlementMode mode,
            int commissionBps,
            Instant updatedAt) {

        public static SettlementConfigResponse from(AgencySettlementConfig config) {
            return new SettlementConfigResponse(
                    config.agencyId, config.mode, config.commissionBps, config.updatedAt);
        }
    }

    /**
     * @param outstanding what is still owed, and {@code overdue} whether it is late.
     *                    Both derived rather than stored - see {@code InvoiceStatus}.
     */
    public record InvoiceResponse(
            UUID id,
            UUID leaseId,
            UUID renterId,
            UUID landlordId,
            String houseReference,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            BigDecimal amount,
            BigDecimal amountPaid,
            BigDecimal outstanding,
            String currency,
            InvoiceStatus status,
            boolean overdue,
            int daysOverdue) {

        public static InvoiceResponse from(Invoice invoice) {
            LocalDate today = LocalDate.now();
            return new InvoiceResponse(
                    invoice.id,
                    invoice.leaseId,
                    invoice.renterId,
                    invoice.landlordId,
                    invoice.houseReference,
                    invoice.periodStart,
                    invoice.periodEnd,
                    invoice.dueDate,
                    invoice.amount,
                    invoice.amountPaid,
                    invoice.outstanding(),
                    invoice.currency,
                    invoice.status,
                    invoice.isOverdueOn(today),
                    invoice.daysOverdueOn(today));
        }
    }

    public record PaymentResponse(
            UUID id,
            UUID invoiceId,
            UUID leaseId,
            UUID renterId,
            BigDecimal amount,
            String currency,
            PaymentMethod method,
            String providerReference,
            PaymentStatus status,
            String failureReason,
            Instant initiatedAt,
            Instant settledAt) {

        public static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.id,
                    payment.invoiceId,
                    payment.leaseId,
                    payment.renterId,
                    payment.amount,
                    payment.currency,
                    payment.method,
                    payment.providerReference,
                    payment.status,
                    payment.failureReason,
                    payment.initiatedAt,
                    payment.settledAt);
        }
    }

    public record PayoutResponse(
            UUID id,
            UUID landlordId,
            UUID paymentId,
            UUID leaseId,
            BigDecimal grossAmount,
            BigDecimal commissionAmount,
            BigDecimal netAmount,
            String currency,
            PayoutStatus status,
            Instant createdAt,
            Instant settledAt) {

        public static PayoutResponse from(Payout payout) {
            return new PayoutResponse(
                    payout.id,
                    payout.landlordId,
                    payout.paymentId,
                    payout.leaseId,
                    payout.grossAmount,
                    payout.commissionAmount,
                    payout.netAmount,
                    payout.currency,
                    payout.status,
                    payout.createdAt,
                    payout.settledAt);
        }
    }

    /**
     * What a renter sees of their own rent.
     *
     * <p>Carries no landlord id. A renter deals with the agency; who owns the building
     * is not theirs to know, and exposing it here would leak the landlord's platform
     * identity to every renter they have ever had.
     */
    public record RenterInvoiceResponse(
            UUID invoiceId,
            String billedByAgency,
            UUID leaseId,
            String houseReference,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            BigDecimal amount,
            BigDecimal amountPaid,
            BigDecimal outstanding,
            String currency,
            InvoiceStatus status,
            boolean overdue) {

        public static RenterInvoiceResponse from(RenterInvoiceView view) {
            return new RenterInvoiceResponse(
                    view.invoiceId,
                    view.agencyId,
                    view.leaseId,
                    view.houseReference,
                    view.periodStart,
                    view.periodEnd,
                    view.dueDate,
                    view.amount,
                    view.amountPaid,
                    view.amount.subtract(view.amountPaid),
                    view.currency,
                    view.status,
                    view.status.isPayable() && view.dueDate.isBefore(LocalDate.now()));
        }
    }

    /**
     * What a landlord sees of money received on their houses.
     *
     * <p>Gross, commission and net are all shown. A landlord told only the net figure
     * will ask where the rest went, and the answer belongs in the same row rather than
     * in a support conversation.
     */
    public record LandlordEarningResponse(
            UUID paymentId,
            String collectedByAgency,
            UUID leaseId,
            String houseReference,
            String renterName,
            BigDecimal grossAmount,
            BigDecimal commissionAmount,
            BigDecimal netAmount,
            String currency,
            Instant settledAt) {

        public static LandlordEarningResponse from(LandlordEarningView earning) {
            return new LandlordEarningResponse(
                    earning.paymentId,
                    earning.agencyId,
                    earning.leaseId,
                    earning.houseReference,
                    earning.renterName,
                    earning.grossAmount,
                    earning.commissionAmount,
                    earning.netAmount,
                    earning.currency,
                    earning.settledAt);
        }
    }

    /** A renter's or landlord's running total, so a client shows one number. */
    public record BalanceResponse(BigDecimal amount, String currency) {
    }

    private PaymentDtos() {
    }
}
