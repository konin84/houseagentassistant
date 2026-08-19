package com.digitalpartner.houseagent.property.service;

import com.digitalpartner.houseagent.property.domain.MarketplaceListing;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Public, cross-agency search over the marketplace projection.
 *
 * <p>{@link MarketplaceListing} carries no {@code @TenantId}, so these queries span
 * every agency by design - that is what makes the marketplace a marketplace. Because
 * the projection only ever contains houses that are both AVAILABLE and published,
 * "only unlet houses are listed" needs no condition here; it is already true of every
 * row in the table.
 */
@ApplicationScoped
public class MarketplaceSearch {

    /** Hard ceiling on page size. Without it, {@code ?size=100000} is a free outage. */
    public static final int MAX_PAGE_SIZE = 100;

    public record Criteria(
            String city,
            String countryCode,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Integer minBedrooms) {
    }

    public record Results(List<MarketplaceListing> items, long total) {
    }

    public Results search(Criteria criteria, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        List<String> clauses = new ArrayList<>();
        Parameters params = new Parameters();

        if (isSet(criteria.city())) {
            // Case-insensitive so "abidjan" and "Abidjan" behave the same. Backed by
            // the lower(city) index in V1; a plain LOWER() comparison without that
            // index degrades to a full scan as the table grows.
            clauses.add("lower(city) = lower(:city)");
            params.and("city", criteria.city());
        }
        if (isSet(criteria.countryCode())) {
            clauses.add("countryCode = :countryCode");
            params.and("countryCode", criteria.countryCode());
        }
        if (criteria.minPrice() != null) {
            clauses.add("pricePerMonth >= :minPrice");
            params.and("minPrice", criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            clauses.add("pricePerMonth <= :maxPrice");
            params.and("maxPrice", criteria.maxPrice());
        }
        if (criteria.minBedrooms() != null) {
            clauses.add("bedrooms >= :minBedrooms");
            params.and("minBedrooms", criteria.minBedrooms());
        }

        Sort newestFirst = Sort.by("listedAt").descending();

        if (clauses.isEmpty()) {
            return new Results(
                    MarketplaceListing.findAll(newestFirst).page(Page.of(safePage, safeSize)).list(),
                    MarketplaceListing.count());
        }

        String query = String.join(" and ", clauses);
        return new Results(
                MarketplaceListing.find(query, newestFirst, params)
                        .page(Page.of(safePage, safeSize))
                        .list(),
                MarketplaceListing.count(query, params));
    }

    public MarketplaceListing findById(java.util.UUID houseId) {
        MarketplaceListing listing = MarketplaceListing.findById(houseId);
        if (listing == null) {
            throw new HouseNotFoundException(houseId);
        }
        return listing;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
