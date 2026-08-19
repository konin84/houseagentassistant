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
import java.util.UUID;

/**
 * One attempt to pay rent against one invoice.
 *
 * <p>A row here is not money received - only a {@link PaymentStatus#SETTLED} one is.
 * Mobile money, which is how rent is actually paid in this market, is a two-step
 * exchange: the renter initiates, then the provider confirms seconds or minutes later.
 * Collapsing those into a single write would mark rent received the moment someone
 * pressed a button.
 */
@Entity
@Table(name = "payment")
public class Payment extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @TenantId
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    public UUID invoiceId;

    @Column(name = "lease_id", nullable = false, updatable = false)
    public UUID leaseId;

    @Column(name = "renter_id", nullable = false, updatable = false)
    public UUID renterId;

    @Column(name = "landlord_id", nullable = false, updatable = false)
    public UUID landlordId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    public PaymentMethod method;

    /**
     * The provider's own transaction id, and this service's idempotency key.
     *
     * <p>A unique index enforces it. Providers retry their callbacks, so the same
     * confirmation will arrive more than once; the second insert is refused by the
     * database and the application returns the payment it already has, rather than
     * taking the renter's money twice.
     *
     * <p>Null for cash, which has no provider and cannot be delivered twice.
     */
    @Column(name = "provider_reference", length = 200)
    public String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "failure_reason", length = 200)
    public String failureReason;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    public Instant initiatedAt = Instant.now();

    /** Set exactly when the status becomes SETTLED, enforced by a check constraint. */
    @Column(name = "settled_at")
    public Instant settledAt;

    @Version
    @Column(name = "version", nullable = false)
    public long version;
}
