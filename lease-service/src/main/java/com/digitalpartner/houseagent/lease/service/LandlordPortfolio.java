package com.digitalpartner.houseagent.lease.service;

import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.LandlordLeaseResponse;
import com.digitalpartner.houseagent.lease.domain.LandlordLeaseView;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A landlord's own tenancies: who is renting which of their houses, on what terms.
 *
 * <h2>The only thing isolating one landlord from another</h2>
 *
 * {@code LandlordLeaseView} carries no {@code @TenantId}, because landlords are not
 * agency-scoped. Nothing in Hibernate will narrow these queries. The
 * {@code landlordId = :landlordId} predicate below <em>is</em> the access control, and
 * the id comes from the validated token, never from the request.
 *
 * <p>Consequently every query in this class must carry that predicate. It is
 * deliberately the whole of a small class rather than a few methods among many, so
 * that adding an unfiltered query here looks as wrong as it is.
 */
@ApplicationScoped
public class LandlordPortfolio {

    public static final int MAX_PAGE_SIZE = 100;

    @Transactional
    public List<LandlordLeaseResponse> leasesOf(UUID landlordId, boolean currentOnly,
                                                int page, int size) {
        String query = currentOnly
                ? "landlordId = :landlordId and status in ('PENDING_MOVE_IN', 'ACTIVE')"
                : "landlordId = :landlordId";

        return LandlordLeaseView.<LandlordLeaseView>find(
                        query,
                        Sort.by("startDate").descending(),
                        Parameters.with("landlordId", landlordId))
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(LandlordLeaseResponse::from)
                .toList();
    }

    @Transactional
    public long countLeasesOf(UUID landlordId, boolean currentOnly) {
        return currentOnly
                ? LandlordLeaseView.count(
                        "landlordId = :landlordId and status in ('PENDING_MOVE_IN', 'ACTIVE')",
                        Parameters.with("landlordId", landlordId))
                : LandlordLeaseView.count("landlordId = :landlordId",
                        Parameters.with("landlordId", landlordId));
    }

    /**
     * A single tenancy belonging to this landlord.
     *
     * <p>The landlord id is part of the lookup rather than checked afterwards, so a
     * lease belonging to someone else is simply not found - the same reasoning that
     * makes a foreign house a 404 in property-service.
     */
    @Transactional
    public LandlordLeaseResponse leaseOf(UUID landlordId, UUID leaseId) {
        LandlordLeaseView view = LandlordLeaseView.find(
                        "leaseId = :leaseId and landlordId = :landlordId",
                        Parameters.with("leaseId", leaseId).and("landlordId", landlordId))
                .firstResult();
        if (view == null) {
            throw new LeaseNotFoundException(leaseId);
        }
        return LandlordLeaseResponse.from(view);
    }
}
