package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.common.events.PaymentEvents;
import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.InitiatePaymentRequest;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.PaymentResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.RecordPaymentRequest;
import com.digitalpartner.houseagent.payment.domain.AgencySettlementConfig;
import com.digitalpartner.houseagent.payment.domain.Invoice;
import com.digitalpartner.houseagent.payment.domain.LandlordEarningView;
import com.digitalpartner.houseagent.payment.domain.LeaseBilling;
import com.digitalpartner.houseagent.payment.domain.Payment;
import com.digitalpartner.houseagent.payment.domain.PaymentMethod;
import com.digitalpartner.houseagent.payment.domain.PaymentStatus;
import com.digitalpartner.houseagent.payment.domain.Payout;
import com.digitalpartner.houseagent.payment.domain.RenterInvoiceView;
import com.digitalpartner.houseagent.payment.outbox.OutboxWriter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Recording money, and the only place allowed to say that rent has been received.
 *
 * <h2>Two steps, not one</h2>
 *
 * A renter <em>initiates</em> a payment and a provider later <em>confirms</em> it.
 * Mobile money - which is how rent is actually paid here - is asynchronous: the push
 * goes to a handset and the callback arrives seconds or minutes later. Only
 * {@link #confirmSettlement} moves money in the model. Cash taken at the office skips
 * the wait and is recorded already settled, because there is nothing to confirm.
 *
 * <h2>Settling once</h2>
 *
 * Providers retry callbacks. The provider's own reference is the idempotency key: it
 * is checked before insert for the ordinary retry, and a unique index refuses it for
 * the concurrent one. Confirming an already-settled payment returns what is already
 * there rather than adding to the invoice a second time.
 */
@ApplicationScoped
public class PaymentService {

    private static final String AGGREGATE = "payment";

    @Inject
    SettlementPolicy policy;

    @Inject
    OutboxWriter outbox;

    // ------------------------------------------------------------ agency path

    /**
     * Records a payment the agency has already received - cash over the counter, or a
     * transfer they have seen land. Settles immediately.
     */
    @Transactional
    public PaymentResponse recordSettled(RecordPaymentRequest request) {
        Invoice invoice = loadInvoice(request.invoiceId());
        rejectDuplicateReference(request.providerReference(), null);

        Payment payment = newPayment(invoice, request.amount(), request.method(),
                request.providerReference());
        payment.persist();

        settle(payment, invoice);
        return PaymentResponse.from(payment);
    }

    /**
     * Confirms a pending payment, normally from a provider callback.
     *
     * <p>Returns the existing payment unchanged if it has already settled. A provider
     * that delivers the same confirmation twice must not be able to credit the invoice
     * twice, and answering "yes, that one" is the correct response to a retry - not an
     * error the provider will keep retrying against.
     */
    @Transactional
    public PaymentResponse confirmSettlement(UUID paymentId, String providerReference) {
        Payment payment = loadPayment(paymentId);

        if (payment.status == PaymentStatus.SETTLED) {
            return PaymentResponse.from(payment);
        }
        if (payment.status == PaymentStatus.FAILED) {
            throw new IllegalPaymentStateException(
                    "Payment " + paymentId + " already failed and cannot be settled");
        }

        // Confirming with the reference the payment was initiated under is the normal
        // case, not a duplicate - the payment already carries it. Only a genuinely
        // different reference is a new claim on that transaction and has to be checked.
        if (providerReference != null && !providerReference.isBlank()) {
            rejectDuplicateReference(providerReference, payment.id);
            payment.providerReference = providerReference;
        }

        settle(payment, loadInvoice(payment.invoiceId));
        return PaymentResponse.from(payment);
    }

    /** Records that the provider rejected the payment. Leaves the invoice untouched. */
    @Transactional
    public PaymentResponse markFailed(UUID paymentId, String reason) {
        Payment payment = loadPayment(paymentId);
        if (payment.status == PaymentStatus.SETTLED) {
            throw new IllegalPaymentStateException(
                    "Payment " + paymentId + " has already settled; money cannot un-move");
        }
        payment.status = PaymentStatus.FAILED;
        payment.failureReason = reason;
        return PaymentResponse.from(payment);
    }

    // ------------------------------------------------------------ renter path

    /**
     * A renter starts paying one of their own invoices.
     *
     * <h2>Why this is not simply {@code @Transactional}</h2>
     *
     * A renter's token carries no {@code agency_id}, so {@link Invoice} - which is
     * tenant-filtered - is invisible to them. Their own projection is not, and it names
     * the agency the invoice belongs to.
     *
     * <p>The write then has to happen in a <em>new</em> transaction. Hibernate binds
     * the tenant when it opens a session, so establishing the agency part-way through
     * an existing transaction would change nothing: the session already resolved to the
     * no-agency sentinel while reading the projection above.
     */
    public PaymentResponse initiateAsRenter(UUID invoiceId, UUID renterId,
                                            InitiatePaymentRequest request) {
        RenterInvoiceView view = AgencyScan.withoutTenant(() ->
                QuarkusTransaction.requiringNew().call(() ->
                        RenterInvoiceView.<RenterInvoiceView>find(
                                        "invoiceId = ?1 and renterId = ?2", invoiceId, renterId)
                                .firstResult()));

        // Someone else's invoice and a non-existent one are indistinguishable on
        // purpose - see InvoiceNotFoundException.
        if (view == null) {
            throw new InvoiceNotFoundException(invoiceId);
        }

        return AgencyContext.callWith(view.agencyId, () ->
                QuarkusTransaction.requiringNew().call(() -> {
                    Invoice invoice = loadInvoice(invoiceId);
                    rejectDuplicateReference(request.providerReference(), null);

                    Payment payment = newPayment(invoice, request.amount(), request.method(),
                            request.providerReference());
                    // Pending, not settled. Nothing has been received yet; the provider
                    // confirms separately.
                    payment.status = PaymentStatus.PENDING;
                    payment.persist();
                    return PaymentResponse.from(payment);
                }));
    }

    // ---------------------------------------------------------------- reading

    @Transactional
    public PaymentResponse get(UUID paymentId) {
        return PaymentResponse.from(loadPayment(paymentId));
    }

    // ---------------------------------------------------------------- internals

    /**
     * The single settlement path. Everything that must be true exactly once when money
     * arrives happens here, so the agency and renter routes cannot drift apart.
     */
    private void settle(Payment payment, Invoice invoice) {
        if (!invoice.status.isPayable()) {
            throw new IllegalPaymentStateException("Invoice " + invoice.id + " is " + invoice.status);
        }
        if (payment.amount.compareTo(invoice.outstanding()) > 0) {
            throw new IllegalPaymentStateException(
                    "Payment of " + payment.amount + " exceeds the " + invoice.outstanding()
                            + " outstanding on invoice " + invoice.id);
        }

        AgencySettlementConfig config = policy.forAgency(invoice.agencyId);
        BigDecimal commission = config.commissionOn(payment.amount);
        BigDecimal net = payment.amount.subtract(commission);

        Instant settledAt = Instant.now();
        payment.status = PaymentStatus.SETTLED;
        payment.settledAt = settledAt;

        invoice.applySettled(payment.amount);
        syncRenterView(invoice);

        LeaseBilling billing = LeaseBilling.findById(invoice.leaseId);
        String renterName = billing == null ? null : billing.renterName;

        // A payout only exists where the platform actually holds the money. Under
        // DIRECT_TO_LANDLORD the absence of a row is the record that nothing is owed.
        if (config.mode.platformHoldsTheMoney()) {
            Payout payout = new Payout();
            payout.agencyId = invoice.agencyId;
            payout.landlordId = invoice.landlordId;
            payout.paymentId = payment.id;
            payout.leaseId = invoice.leaseId;
            payout.grossAmount = payment.amount;
            payout.commissionAmount = commission;
            payout.netAmount = net;
            payout.currency = payment.currency;
            payout.persist();
        }

        LandlordEarningView earning = new LandlordEarningView();
        earning.paymentId = payment.id;
        earning.landlordId = invoice.landlordId;
        earning.agencyId = invoice.agencyId;
        earning.leaseId = invoice.leaseId;
        earning.houseReference = invoice.houseReference;
        earning.renterName = renterName;
        earning.grossAmount = payment.amount;
        earning.commissionAmount = commission;
        earning.netAmount = net;
        earning.currency = payment.currency;
        earning.settledAt = settledAt;
        earning.persist();

        outbox.record(AGGREGATE, payment.id, invoice.agencyId, "PaymentSettled",
                new PaymentEvents.PaymentSettled(
                        payment.id, invoice.id, invoice.leaseId, invoice.agencyId,
                        invoice.renterId, renterName, invoice.landlordId, invoice.houseReference,
                        payment.amount, commission, net, payment.currency,
                        invoice.periodStart, invoice.periodEnd,
                        payment.providerReference, settledAt));
    }

    private Payment newPayment(Invoice invoice, BigDecimal amount, PaymentMethod method,
                               String providerReference) {
        if (method.requiresProviderReference()
                && (providerReference == null || providerReference.isBlank())) {
            throw new IllegalPaymentStateException(
                    method + " payments must quote the provider's reference, "
                            + "which is what makes a retried callback safe");
        }

        Payment payment = new Payment();
        payment.agencyId = invoice.agencyId;
        payment.invoiceId = invoice.id;
        payment.leaseId = invoice.leaseId;
        payment.renterId = invoice.renterId;
        payment.landlordId = invoice.landlordId;
        payment.amount = amount;
        payment.currency = invoice.currency;
        payment.method = method;
        payment.providerReference = providerReference;
        return payment;
    }

    /**
     * Catches the ordinary case: a provider retrying a callback it already delivered.
     *
     * <p>The unique index on {@code provider_reference} is what catches the harder one,
     * two callbacks racing, where both would pass this check before either inserted.
     *
     * @param excluding the payment currently being worked on, which legitimately already
     *                  holds this reference. Null when creating a new payment, where
     *                  any existing match is by definition somebody else's.
     */
    private void rejectDuplicateReference(String providerReference, UUID excluding) {
        if (providerReference == null || providerReference.isBlank()) {
            return;
        }
        Payment existing = Payment.<Payment>find("providerReference", providerReference)
                .firstResult();
        if (existing != null && !existing.id.equals(excluding)) {
            throw new DuplicatePaymentException(providerReference, existing.id);
        }
    }

    private void syncRenterView(Invoice invoice) {
        RenterInvoiceView view = RenterInvoiceView.findById(invoice.id);
        if (view == null) {
            new RenterInvoiceView().copyFrom(invoice).persist();
        } else {
            view.copyFrom(invoice);
        }
    }

    private Invoice loadInvoice(UUID id) {
        Invoice invoice = Invoice.findById(id);
        if (invoice == null) {
            throw new InvoiceNotFoundException(id);
        }
        return invoice;
    }

    private Payment loadPayment(UUID id) {
        Payment payment = Payment.findById(id);
        if (payment == null) {
            throw new PaymentNotFoundException(id);
        }
        return payment;
    }
}
