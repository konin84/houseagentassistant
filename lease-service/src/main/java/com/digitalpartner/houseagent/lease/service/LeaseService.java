package com.digitalpartner.houseagent.lease.service;

import com.digitalpartner.houseagent.common.events.LeaseEvents;
import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.LeaseResponse;
import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.SignLeaseRequest;
import com.digitalpartner.houseagent.lease.domain.LandlordLeaseView;
import com.digitalpartner.houseagent.lease.domain.Lease;
import com.digitalpartner.houseagent.lease.domain.LeaseStatus;
import com.digitalpartner.houseagent.lease.domain.PaymentModality;
import com.digitalpartner.houseagent.lease.outbox.OutboxWriter;
import com.digitalpartner.houseagent.lease.security.AgencyTenantResolver;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lease lifecycle, and the source of truth for whether a house is occupied.
 *
 * <p>Every state change writes an outbox event in the same transaction, so
 * property-service can keep its marketplace in step without this service calling it.
 */
@ApplicationScoped
public class LeaseService {

    /** PostgreSQL SQLSTATE for exclusion_violation. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private static final String AGGREGATE = "lease";

    @Inject
    OutboxWriter outbox;

    /**
     * Qualified explicitly: {@code @PersistenceUnitExtension} is itself a CDI
     * qualifier, so the resolver is not a {@code @Default} bean and cannot be
     * injected without naming it.
     */
    @Inject
    @PersistenceUnitExtension
    AgencyTenantResolver tenantResolver;

    /**
     * Signs a new lease, or fails if the house is already spoken for.
     *
     * <p>Deliberately does <em>not</em> query for a conflicting lease first. Two agents
     * signing the same house concurrently would both see it free before either wrote.
     * Instead the insert is flushed immediately and the database's exclusion
     * constraint decides - exactly one transaction can win, whatever the timing.
     */
    @Transactional
    public LeaseResponse sign(SignLeaseRequest request) {
        Lease lease = new Lease();
        lease.houseId = request.houseId();
        lease.renterId = request.renterId();
        lease.renterName = request.renterName();
        lease.renterPhone = request.renterPhone();
        lease.landlordId = request.landlordId();
        lease.houseReference = request.houseReference();
        lease.modality = new PaymentModality(
                request.rentAmount(),
                request.currency(),
                request.cadence(),
                request.dueDayOfMonth(),
                request.depositAmount());
        lease.startDate = request.startDate();
        lease.endDate = request.endDate();
        lease.status = LeaseStatus.PENDING_MOVE_IN;

        try {
            lease.persist();
            // Force the INSERT now so the constraint is evaluated here, where it can
            // be translated into a 409, rather than at commit where it surfaces as an
            // opaque 500.
            Panache.flush();
        } catch (RuntimeException e) {
            if (isOverlapViolation(e)) {
                throw new HouseAlreadyLetException(request.houseId());
            }
            throw e;
        }

        String agencyId = tenantResolver.resolveTenantId();
        lease.agencyId = agencyId;

        syncLandlordView(lease);
        outbox.record(AGGREGATE, lease.id, agencyId, "LeaseSigned",
                new LeaseEvents.LeaseSigned(
                        lease.id, lease.houseId, agencyId,
                        lease.renterId, lease.renterName,
                        lease.landlordId, lease.houseReference,
                        lease.modality.rentAmount, lease.modality.currency,
                        lease.modality.cadence, lease.modality.dueDayOfMonth,
                        lease.startDate, lease.endDate, Instant.now()));

        return LeaseResponse.from(lease);
    }

    /** Marks the renter as moved in. The house was already unavailable before this. */
    @Transactional
    public LeaseResponse activate(UUID id) {
        Lease lease = load(id);
        if (lease.status != LeaseStatus.PENDING_MOVE_IN) {
            throw new IllegalLeaseStateException(
                    "Only a PENDING_MOVE_IN lease can be activated, this one is " + lease.status);
        }
        lease.status = LeaseStatus.ACTIVE;
        lease.updatedAt = Instant.now();

        String agencyId = tenantResolver.resolveTenantId();
        syncLandlordView(lease);
        outbox.record(AGGREGATE, lease.id, agencyId, "LeaseActivated",
                new LeaseEvents.LeaseActivated(
                        lease.id, lease.houseId, agencyId, Instant.now()));

        return LeaseResponse.from(lease);
    }

    /**
     * Ends a lease, releasing the house back to the market.
     *
     * <p>Covers expiry, early termination and cancellation before move-in; the
     * resulting status differs but the effect on availability is the same, which is
     * why both emit one {@code LeaseEnded} event.
     */
    @Transactional
    public LeaseResponse end(UUID id, String reason) {
        Lease lease = load(id);
        if (!lease.occupiesHouse()) {
            throw new IllegalLeaseStateException("Lease is already " + lease.status);
        }

        lease.status = lease.status == LeaseStatus.PENDING_MOVE_IN
                ? LeaseStatus.CANCELLED
                : LeaseStatus.ENDED;
        lease.endReason = reason;
        lease.updatedAt = Instant.now();

        String agencyId = tenantResolver.resolveTenantId();
        syncLandlordView(lease);
        outbox.record(AGGREGATE, lease.id, agencyId, "LeaseEnded",
                new LeaseEvents.LeaseEnded(
                        lease.id, lease.houseId, agencyId,
                        reason == null ? lease.status.name() : reason, Instant.now()));

        return LeaseResponse.from(lease);
    }

    @Transactional
    public LeaseResponse get(UUID id) {
        return LeaseResponse.from(load(id));
    }

    @Transactional
    public List<LeaseResponse> list(int page, int size) {
        return Lease.<Lease>findAll(Sort.by("createdAt").descending())
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(LeaseResponse::from)
                .toList();
    }

    @Transactional
    public long count() {
        return Lease.count();
    }

    /** Keeps the landlord's projection in step. Idempotent, keyed on the lease id. */
    private void syncLandlordView(Lease lease) {
        LandlordLeaseView view = LandlordLeaseView.findById(lease.id);
        if (view == null) {
            new LandlordLeaseView().copyFrom(lease).persist();
        } else {
            view.copyFrom(lease);
        }
    }

    private Lease load(UUID id) {
        Lease lease = Lease.findById(id);
        if (lease == null) {
            throw new LeaseNotFoundException(id);
        }
        return lease;
    }

    /**
     * True when the cause chain carries PostgreSQL's exclusion_violation.
     *
     * <p>Matching on SQLSTATE rather than the message text: messages are localised and
     * change between server versions, whereas 23P01 is fixed by the standard.
     */
    private static boolean isOverlapViolation(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
