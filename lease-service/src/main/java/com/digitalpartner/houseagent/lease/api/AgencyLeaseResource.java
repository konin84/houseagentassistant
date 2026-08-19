package com.digitalpartner.houseagent.lease.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.EndLeaseRequest;
import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.LeaseResponse;
import com.digitalpartner.houseagent.lease.api.dto.LeaseDtos.SignLeaseRequest;
import com.digitalpartner.houseagent.lease.api.dto.PageResponse;
import com.digitalpartner.houseagent.lease.security.CallerContext;
import com.digitalpartner.houseagent.lease.service.LeaseService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Lease management for agency staff. Scoped to the caller's own agency throughout.
 */
@Path("/api/agency/leases")
@Tag(name = "Agency leases", description = "Lease management for agency staff")
@RolesAllowed({Roles.AGENT, Roles.AGENCY_ADMIN})
@Produces(MediaType.APPLICATION_JSON)
public class AgencyLeaseResource {

    @Inject
    LeaseService leases;

    @Inject
    CallerContext caller;

    /**
     * Signs a lease. Returns 409 if the house already has one covering that period -
     * see the exclusion constraint in {@code V1__lease_schema.sql}.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sign(@Valid SignLeaseRequest request) {
        caller.requireAgencyId();
        LeaseResponse signed = leases.sign(request);
        return Response.created(URI.create("/api/agency/leases/" + signed.id()))
                .entity(signed)
                .build();
    }

    @GET
    public PageResponse<LeaseResponse> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        caller.requireAgencyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<LeaseResponse> items = leases.list(safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, leases.count());
    }

    @GET
    @Path("/{id}")
    public LeaseResponse get(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return leases.get(id);
    }

    /** Records that the renter has moved in. */
    @POST
    @Path("/{id}/activation")
    public LeaseResponse activate(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return leases.activate(id);
    }

    /** Ends the lease and releases the house back to the marketplace. */
    @POST
    @Path("/{id}/termination")
    @Consumes(MediaType.APPLICATION_JSON)
    public LeaseResponse end(@PathParam("id") UUID id, EndLeaseRequest request) {
        caller.requireAgencyId();
        return leases.end(id, request == null ? null : request.reason());
    }
}
