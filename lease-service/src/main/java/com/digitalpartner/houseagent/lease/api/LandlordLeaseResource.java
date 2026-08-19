package com.digitalpartner.houseagent.lease.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.LandlordLeaseResponse;
import com.digitalpartner.houseagent.lease.api.dto.PageResponse;
import com.digitalpartner.houseagent.lease.security.CallerContext;
import com.digitalpartner.houseagent.lease.service.LandlordPortfolio;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * A landlord's own portfolio: which of their houses is let, to whom, on what terms.
 *
 * <p>This answers "landlords know who is renting which house and the modality of
 * payment" directly.
 *
 * <p>Note the absence of a {@code landlordId} parameter on every method. The identity
 * comes from the token. Accepting it from the path would turn this resource into a way
 * for any landlord to read every other landlord's tenancies, and because landlords are
 * not agency-scoped there is no tenant filter underneath to catch the mistake.
 */
@Path("/api/landlord/leases")
@Tag(name = "Landlord portfolio", description = "A landlord's own tenancies")
@RolesAllowed(Roles.LANDLORD)
@Produces(MediaType.APPLICATION_JSON)
public class LandlordLeaseResource {

    @Inject
    LandlordPortfolio portfolio;

    @Inject
    CallerContext caller;

    /**
     * @param currentOnly when true, only tenancies presently occupying a house;
     *                    when false, the full history including ended leases
     */
    @GET
    public PageResponse<LandlordLeaseResponse> myLeases(
            @QueryParam("currentOnly") @DefaultValue("true") boolean currentOnly,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        UUID landlordId = caller.requirePartyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), LandlordPortfolio.MAX_PAGE_SIZE);

        List<LandlordLeaseResponse> items =
                portfolio.leasesOf(landlordId, currentOnly, safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize,
                portfolio.countLeasesOf(landlordId, currentOnly));
    }

    @GET
    @Path("/{leaseId}")
    public LandlordLeaseResponse myLease(@PathParam("leaseId") UUID leaseId) {
        return portfolio.leaseOf(caller.requirePartyId(), leaseId);
    }
}
