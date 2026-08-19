package com.digitalpartner.houseagent.property.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.property.api.dto.HouseDtos.CreateHouseRequest;
import com.digitalpartner.houseagent.property.api.dto.HouseDtos.HouseResponse;
import com.digitalpartner.houseagent.property.api.dto.HouseDtos.UpdateHouseRequest;
import com.digitalpartner.houseagent.property.api.dto.PageResponse;
import com.digitalpartner.houseagent.property.security.CallerContext;
import com.digitalpartner.houseagent.property.service.HouseService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
 * Agency-facing house management. Every endpoint operates strictly inside the
 * caller's own agency.
 *
 * <p>There is no {@code agencyId} path or query parameter anywhere in this resource,
 * and that is deliberate: the agency comes from the validated token, so there is
 * nothing for a caller to tamper with.
 *
 * <p>{@code @Consumes} sits on the individual methods that actually take a body
 * rather than on the class. At class level it would also apply to the body-less
 * publish/unpublish calls, which then reject a plain POST with 415.
 */
@Path("/api/agency/houses")
@Tag(name = "Agency houses", description = "House management for agency staff")
@RolesAllowed({Roles.AGENT, Roles.AGENCY_ADMIN})
@Produces(MediaType.APPLICATION_JSON)
public class AgencyHouseResource {

    @Inject
    HouseService houses;

    @Inject
    CallerContext caller;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@Valid CreateHouseRequest request) {
        // Rejects a token that carries an agency role but no agency claim, before any
        // database work happens.
        caller.requireAgencyId();

        HouseResponse created = houses.create(request);
        return Response.created(URI.create("/api/agency/houses/" + created.id()))
                .entity(created)
                .build();
    }

    @GET
    public PageResponse<HouseResponse> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        caller.requireAgencyId();

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<HouseResponse> items = houses.list(safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, houses.count());
    }

    @GET
    @Path("/{id}")
    public HouseResponse get(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return houses.get(id);
    }

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public HouseResponse update(@PathParam("id") UUID id, @Valid UpdateHouseRequest request) {
        caller.requireAgencyId();
        return houses.update(id, request);
    }

    /** Advertises the house on the public marketplace. */
    @POST
    @Path("/{id}/publication")
    public HouseResponse publish(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return houses.publish(id);
    }

    /** Withdraws the house from the marketplace without changing its status. */
    @DELETE
    @Path("/{id}/publication")
    public HouseResponse unpublish(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return houses.unpublish(id);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed(Roles.AGENCY_ADMIN)
    public Response delete(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        houses.delete(id);
        return Response.noContent().build();
    }
}
