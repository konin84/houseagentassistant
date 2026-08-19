package com.digitalpartner.houseagent.property.service;

import com.digitalpartner.houseagent.property.api.dto.HouseDtos.AddressDto;
import com.digitalpartner.houseagent.property.api.dto.HouseDtos.CreateHouseRequest;
import com.digitalpartner.houseagent.property.api.dto.HouseDtos.HouseResponse;
import com.digitalpartner.houseagent.property.api.dto.HouseDtos.UpdateHouseRequest;
import com.digitalpartner.houseagent.property.domain.Address;
import com.digitalpartner.houseagent.property.domain.AvailabilityStatus;
import com.digitalpartner.houseagent.property.domain.House;
import com.digitalpartner.houseagent.property.images.CloudinaryUrls;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agency-scoped house management.
 *
 * <p>Every query here is silently narrowed to the caller's agency by Hibernate's
 * tenant filter, so no method mentions {@code agencyId}. That is the point of the
 * discriminator strategy: isolation is not something each query has to remember.
 *
 * <p>These methods return DTOs rather than entities, and every one of them is
 * transactional. Returning a detached {@code House} and mapping it in the resource
 * would blow up on the lazy {@code images} collection the moment the transaction
 * closed - the mapping has to happen while a session is still open.
 */
@ApplicationScoped
public class HouseService {

    @Inject
    ListingProjector listings;

    /** Renders stored Cloudinary public ids into URLs a client can use. */
    @Inject
    CloudinaryUrls urls;

    @Transactional
    public HouseResponse create(CreateHouseRequest request) {
        House house = new House();
        house.landlordId = request.landlordId();
        house.title = request.title();
        house.description = request.description();
        house.address = toAddress(request.address());
        house.bedrooms = request.bedrooms();
        house.bathrooms = request.bathrooms();
        house.sizeSqm = request.sizeSqm();
        house.pricePerMonth = request.pricePerMonth();
        house.currency = request.currency();
        house.status = AvailabilityStatus.AVAILABLE;
        // Never advertised automatically - an agent publishes once photos and price
        // are right.
        house.published = false;
        house.persist();
        return HouseResponse.from(house, urls);
    }

    @Transactional
    public HouseResponse get(UUID id) {
        return HouseResponse.from(load(id), urls);
    }

    @Transactional
    public List<HouseResponse> list(int page, int size) {
        return House.<House>findAll(Sort.by("createdAt").descending())
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(house -> HouseResponse.from(house, urls))
                .toList();
    }

    @Transactional
    public long count() {
        return House.count();
    }

    @Transactional
    public HouseResponse update(UUID id, UpdateHouseRequest request) {
        House house = load(id);

        if (request.title() != null) {
            house.title = request.title();
        }
        if (request.description() != null) {
            house.description = request.description();
        }
        if (request.address() != null) {
            house.address = toAddress(request.address());
        }
        if (request.bedrooms() != null) {
            house.bedrooms = request.bedrooms();
        }
        if (request.bathrooms() != null) {
            house.bathrooms = request.bathrooms();
        }
        if (request.sizeSqm() != null) {
            house.sizeSqm = request.sizeSqm();
        }
        if (request.pricePerMonth() != null) {
            house.pricePerMonth = request.pricePerMonth();
        }
        if (request.currency() != null) {
            house.currency = request.currency();
        }
        if (request.status() != null) {
            requireAgentSettableStatus(request.status());
            house.status = request.status();
        }
        house.updatedAt = Instant.now();

        // A house that just became OCCUPIED must leave the marketplace, and an edited
        // price must be reflected there. Both are this one call.
        listings.sync(house);
        return HouseResponse.from(house, urls);
    }

    /** Advertises the house, provided it is actually available to let. */
    @Transactional
    public HouseResponse publish(UUID id) {
        House house = load(id);
        if (!house.status.isListable()) {
            throw new IllegalHouseStateException(
                    "Cannot publish a house whose status is " + house.status);
        }
        house.published = true;
        house.updatedAt = Instant.now();
        listings.sync(house);
        return HouseResponse.from(house, urls);
    }

    @Transactional
    public HouseResponse unpublish(UUID id) {
        House house = load(id);
        house.published = false;
        house.updatedAt = Instant.now();
        listings.sync(house);
        return HouseResponse.from(house, urls);
    }

    @Transactional
    public void delete(UUID id) {
        House house = load(id);
        listings.remove(house);
        house.delete();
    }

    /**
     * Rejects the statuses that are derived from leases rather than chosen.
     *
     * <p>Since Phase 2, whether a house is occupied is a consequence of a lease
     * existing in lease-service, and arrives here as an event. Letting an agent type
     * OCCUPIED directly would put this service's view at odds with the service that
     * actually knows - and, worse, would let a house be taken off the market without
     * any lease to justify it.
     *
     * <p>AVAILABLE and UNAVAILABLE remain the agency's to set: withdrawing a house for
     * renovation is a business decision, not a derived fact.
     */
    private static void requireAgentSettableStatus(AvailabilityStatus status) {
        if (status == AvailabilityStatus.RESERVED || status == AvailabilityStatus.OCCUPIED) {
            throw new IllegalHouseStateException(
                    status + " is derived from leases and cannot be set directly; "
                            + "sign or end a lease in lease-service instead");
        }
    }

    /**
     * Loads a house from the caller's own agency, or fails.
     *
     * <p>A house belonging to another agency is invisible to the tenant filter and so
     * arrives here as null - indistinguishable from one that never existed, which is
     * exactly the behaviour we want.
     */
    private House load(UUID id) {
        House house = House.findById(id);
        if (house == null) {
            throw new HouseNotFoundException(id);
        }
        return house;
    }

    private static Address toAddress(AddressDto dto) {
        Address address = new Address(dto.street(), dto.district(), dto.city(), dto.countryCode());
        address.latitude = dto.latitude();
        address.longitude = dto.longitude();
        return address;
    }
}
