package com.digitalpartner.houseagent.property.api;

import com.digitalpartner.houseagent.property.api.dto.ListingResponse;
import com.digitalpartner.houseagent.property.api.dto.PageResponse;
import com.digitalpartner.houseagent.property.images.CloudinaryUrls;
import com.digitalpartner.houseagent.property.service.MarketplaceSearch;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Public house search. Anonymous, read-only, and cross-agency by design.
 *
 * <p>This is the busiest endpoint on the platform and the only one an unauthenticated
 * visitor can reach, which is exactly why it reads from the {@code marketplace_listing}
 * projection rather than from {@code house}: the projection contains no landlord
 * identity, no internal notes and no unlet or unpublished houses, so there is nothing
 * here to leak even if the query is wrong.
 */
@Path("/api/marketplace/listings")
@Tag(name = "Marketplace", description = "Public house search, no authentication required")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class MarketplaceResource {

    @Inject
    MarketplaceSearch search;

    @Inject
    CloudinaryUrls urls;

    @GET
    public PageResponse<ListingResponse> search(
            @QueryParam("city") String city,
            @QueryParam("country") String countryCode,
            @QueryParam("minPrice") BigDecimal minPrice,
            @QueryParam("maxPrice") BigDecimal maxPrice,
            @QueryParam("minBedrooms") Integer minBedrooms,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        MarketplaceSearch.Criteria criteria =
                new MarketplaceSearch.Criteria(city, countryCode, minPrice, maxPrice, minBedrooms);
        MarketplaceSearch.Results results = search.search(criteria, page, size);

        List<ListingResponse> items = results.items().stream()
                .map(listing -> ListingResponse.from(listing, urls))
                .toList();
        int safeSize = Math.min(Math.max(size, 1), MarketplaceSearch.MAX_PAGE_SIZE);
        return PageResponse.of(items, Math.max(page, 0), safeSize, results.total());
    }

    @GET
    @Path("/{houseId}")
    public ListingResponse get(@PathParam("houseId") UUID houseId) {
        return ListingResponse.from(search.findById(houseId), urls);
    }
}
