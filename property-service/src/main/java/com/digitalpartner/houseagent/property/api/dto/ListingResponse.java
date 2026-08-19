package com.digitalpartner.houseagent.property.api.dto;

import com.digitalpartner.houseagent.property.domain.MarketplaceListing;
import com.digitalpartner.houseagent.property.images.CloudinaryUrls;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A house as an anonymous renter sees it.
 *
 * <p>Note what is missing: no landlord id, no internal notes, no agency margin. The
 * projection behind this DTO already excludes them, and this record is the second
 * place that decision is enforced.
 */
public record ListingResponse(
        UUID houseId,
        String agencyId,
        String title,
        String description,
        String district,
        String city,
        String countryCode,
        int bedrooms,
        int bathrooms,
        Integer sizeSqm,
        BigDecimal pricePerMonth,
        String currency,
        /**
         * A card-sized image, or null when the house has no photograph. Rendered from
         * the stored public id per request, so changing the crop or moving to a CDN is
         * a code change rather than a migration.
         */
        String coverImageUrl,
        Instant listedAt) {

    public static ListingResponse from(MarketplaceListing listing, CloudinaryUrls urls) {
        return new ListingResponse(
                listing.houseId,
                listing.agencyId,
                listing.title,
                listing.description,
                listing.district,
                listing.city,
                listing.countryCode,
                listing.bedrooms,
                listing.bathrooms,
                listing.sizeSqm,
                listing.pricePerMonth,
                listing.currency,
                urls.thumbnailUrl(listing.coverImagePublicId),
                listing.listedAt);
    }
}
