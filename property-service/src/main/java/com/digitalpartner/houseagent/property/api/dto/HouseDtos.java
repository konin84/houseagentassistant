package com.digitalpartner.houseagent.property.api.dto;

import com.digitalpartner.houseagent.property.domain.AvailabilityStatus;
import com.digitalpartner.houseagent.property.domain.House;
import com.digitalpartner.houseagent.property.images.CloudinaryUrls;
import com.digitalpartner.houseagent.property.images.ImageDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the agency-facing house API.
 *
 * <p>Kept separate from the entities on purpose. Serving {@code House} directly would
 * publish {@code agencyId} and {@code version} to clients and, more dangerously, let a
 * client set them on the way in.
 */
public final class HouseDtos {

    public record AddressDto(
            @NotBlank @Size(max = 200) String street,
            @Size(max = 120) String district,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be a 2-letter ISO country code")
            String countryCode,
            Double latitude,
            Double longitude) {
    }

    public record CreateHouseRequest(
            @NotNull UUID landlordId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull @Valid AddressDto address,
            @Min(0) int bedrooms,
            @Min(0) int bathrooms,
            @Min(1) Integer sizeSqm,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal pricePerMonth,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code")
            String currency) {
    }

    /** Every field optional: absent means "leave unchanged". */
    public record UpdateHouseRequest(
            @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @Valid AddressDto address,
            @Min(0) Integer bedrooms,
            @Min(0) Integer bathrooms,
            @Min(1) Integer sizeSqm,
            @DecimalMin(value = "0.0", inclusive = false) BigDecimal pricePerMonth,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            AvailabilityStatus status) {
    }

    // Images have their own DTOs, in the images package, because they carry rendered
    // Cloudinary URLs and therefore need the account configuration to build.

    public record HouseResponse(
            UUID id,
            UUID landlordId,
            String title,
            String description,
            AddressDto address,
            int bedrooms,
            int bathrooms,
            Integer sizeSqm,
            BigDecimal pricePerMonth,
            String currency,
            AvailabilityStatus status,
            boolean published,
            boolean visibleToPublic,
            List<ImageDtos.ImageResponse> images,
            Instant createdAt,
            Instant updatedAt) {

        public static HouseResponse from(House house, CloudinaryUrls urls) {
            return new HouseResponse(
                    house.id,
                    house.landlordId,
                    house.title,
                    house.description,
                    house.address == null ? null : new AddressDto(
                            house.address.street,
                            house.address.district,
                            house.address.city,
                            house.address.countryCode,
                            house.address.latitude,
                            house.address.longitude),
                    house.bedrooms,
                    house.bathrooms,
                    house.sizeSqm,
                    house.pricePerMonth,
                    house.currency,
                    house.status,
                    house.published,
                    house.isVisibleToPublic(),
                    house.images.stream()
                            .map(image -> ImageDtos.ImageResponse.from(image, urls))
                            .toList(),
                    house.createdAt,
                    house.updatedAt);
        }
    }

    private HouseDtos() {
    }
}
