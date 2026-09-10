package com.digitalpartner.houseagent.agency.api;

import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.AgencyResponse;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.ChangePlanRequest;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.CreateStaffRequest;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.ProvisionedResponse;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.RegisterAgencyRequest;
import com.digitalpartner.houseagent.agency.domain.AgencyStatus;
import com.digitalpartner.houseagent.agency.service.AgencyRegistry;
import com.digitalpartner.houseagent.common.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Running the platform itself: creating agencies and giving each its first
 * administrator.
 *
 * <p>{@code PLATFORM_ADMIN} only, and it is the one role that deliberately carries no
 * {@code agency_id}. It stands outside every agency, which is exactly why it can create
 * them - and also why it is refused by every endpoint on
 * {@link AgencyAdminResource}, which needs an agency to act within.
 */
@Path("/api/platform/agencies")
@Tag(name = "Platform administration", description = "Creating and suspending agencies")
@RolesAllowed(Roles.PLATFORM_ADMIN)
@Produces(MediaType.APPLICATION_JSON)
public class PlatformAgencyResource {

    @Inject
    AgencyRegistry agencies;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@Valid RegisterAgencyRequest request) {
        var agency = agencies.register(
                request.agencyId(), request.name(), request.city(),
                request.countryCode(), request.contactEmail(), request.contactPhone());
        return Response.status(Response.Status.CREATED)
                .entity(AgencyResponse.from(agency))
                .build();
    }

    @GET
    public List<AgencyResponse> list() {
        return agencies.all().stream().map(AgencyResponse::from).toList();
    }

    @GET
    @Path("/{agencyId}")
    public AgencyResponse get(@PathParam("agencyId") String agencyId) {
        return AgencyResponse.from(agencies.require(agencyId));
    }

    /**
     * Gives a new agency the administrator that can then run it.
     *
     * <p>Somebody has to break the circle from outside: adding staff requires being an
     * agency admin, so a brand new agency cannot appoint its own first one.
     */
    @POST
    @Path("/{agencyId}/administrators")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addAdministrator(@PathParam("agencyId") String agencyId,
                                     @Valid CreateStaffRequest request) {
        var provisioned = agencies.addFirstAdmin(
                agencyId, request.email(), request.firstName(), request.lastName(),
                request.phone());
        return Response.status(Response.Status.CREATED)
                .entity(ProvisionedResponse.from(provisioned))
                .build();
    }

    /**
     * Moves an agency to a different subscription plan.
     *
     * <p>A downgrade never removes anything. An agency holding twenty-five houses that
     * moves to the free five keeps all twenty-five and is refused the twenty-sixth -
     * because hiding the excess would pull a landlord's advertisement off the market
     * over a billing decision they had no part in.
     */
    @PUT
    @Path("/{agencyId}/plan")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgencyResponse changePlan(@PathParam("agencyId") String agencyId,
                                     @Valid ChangePlanRequest request) {
        return AgencyResponse.from(agencies.changePlan(agencyId, request.plan()));
    }

    /** Stops an agency operating without deleting anything it is responsible for. */
    @POST
    @Path("/{agencyId}/suspension")
    public AgencyResponse suspend(@PathParam("agencyId") String agencyId) {
        return AgencyResponse.from(agencies.setStatus(agencyId, AgencyStatus.SUSPENDED));
    }

    @POST
    @Path("/{agencyId}/reinstatement")
    public AgencyResponse reinstate(@PathParam("agencyId") String agencyId) {
        return AgencyResponse.from(agencies.setStatus(agencyId, AgencyStatus.ACTIVE));
    }
}
