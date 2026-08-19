package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.LandlordEarningResponse;
import com.digitalpartner.houseagent.payment.domain.LandlordEarningView;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a landlord has actually been paid, across every agency managing a house for
 * them.
 *
 * <p>Same shape and same reasoning as {@link RenterLedger}: no tenant filter exists for
 * a platform-wide principal, so the {@code landlordId} predicate taken from the token
 * is the entire access control, and it lives in one small class on purpose.
 */
@ApplicationScoped
public class LandlordEarnings {

    public static final int MAX_PAGE_SIZE = 100;

    @Transactional
    public List<LandlordEarningResponse> earningsOf(UUID landlordId, int page, int size) {
        return LandlordEarningView.<LandlordEarningView>find(
                        "landlordId = :landlordId",
                        Sort.by("settledAt").descending(),
                        Parameters.with("landlordId", landlordId))
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(LandlordEarningResponse::from)
                .toList();
    }

    @Transactional
    public long countEarningsOf(UUID landlordId) {
        return LandlordEarningView.count("landlordId = :landlordId",
                Parameters.with("landlordId", landlordId));
    }

    /**
     * Total received by this landlord, net of commission.
     *
     * <p>Sums the stored net amounts rather than applying today's commission rate to
     * the gross. A rate change must not retroactively alter what a landlord was paid
     * last year.
     */
    @Transactional
    public BigDecimal totalNetReceived(UUID landlordId) {
        return LandlordEarningView.<LandlordEarningView>find("landlordId = :landlordId",
                        Parameters.with("landlordId", landlordId))
                .list()
                .stream()
                .map(e -> e.netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
