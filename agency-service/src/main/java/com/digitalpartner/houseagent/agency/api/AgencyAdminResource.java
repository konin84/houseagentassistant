package com.digitalpartner.houseagent.agency.api;

import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.AgencyResponse;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.CreateStaffRequest;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.OnboardPartyRequest;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.ProvisionedResponse;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.UpdateAgencyRequest;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.UserResponse;
import com.digitalpartner.houseagent.agency.security.CallerContext;
import com.digitalpartner.houseagent.agency.service.AgencyRegistry;
import com.digitalpartner.houseagent.agency.service.PartyDirectory;
import com.digitalpartner.houseagent.agency.service.StaffDirectory;
import com.digitalpartner.houseagent.common.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * What an agency administrator can do: run their own agency, and put people in it.
 *
 * <p>{@code AGENCY_ADMIN} throughout. An {@code AGENT} sells houses; they do not decide
 * who else works here.
 *
 * <p>No endpoint takes an agency. It comes from the token on every one of them, which
 * is what stops an admin provisioning into somebody else's agency - and that matters
 * more here than anywhere, because the {@code agency_id} stamped on a new account is
 * the value every other service on the platform filters its data by.
 */
@Path("/api/agency")
@Tag(name = "Agency administration", description = "An agency's own profile and people")
@RolesAllowed(Roles.AGENCY_ADMIN)
@Produces(MediaType.APPLICATION_JSON)
public class AgencyAdminResource {

    @Inject
    AgencyRegistry agencies;

    @Inject
    StaffDirectory staff;

    @Inject
    PartyDirectory parties;

    @Inject
    CallerContext caller;

    // ------------------------------------------------------------- the agency

    @GET
    @Path("/profile")
    public AgencyResponse profile() {
        return AgencyResponse.from(agencies.require(caller.requireAgencyId()));
    }

    /** The id and the status are not the agency's own to change. */
    @PATCH
    @Path("/profile")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgencyResponse updateProfile(@Valid UpdateAgencyRequest request) {
        return AgencyResponse.from(agencies.updateOwnProfile(
                caller.requireAgencyId(), request.name(), request.city(),
                request.countryCode(), request.contactEmail(), request.contactPhone()));
    }

    // -------------------------------------------------------------- the staff

    /**
     * Adds an agent to this agency.
     *
     * <p>Only `AGENT`. An admin cannot create another admin - a role able to grant
     * itself would mean one compromised account becoming as many as somebody liked.
     * A second admin is a platform-admin operation.
     */
    @POST
    @Path("/staff")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addStaff(@Valid CreateStaffRequest request) {
        var provisioned = staff.addStaff(
                caller.requireAgencyId(), request.email(),
                request.firstName(), request.lastName(), Roles.AGENT);
        return Response.status(Response.Status.CREATED)
                .entity(ProvisionedResponse.from(provisioned))
                .build();
    }

    @GET
    @Path("/staff")
    public List<UserResponse> listStaff() {
        return staff.staffOf(caller.requireAgencyId()).stream()
                .map(UserResponse::from)
                .toList();
    }

    /**
     * Withdraws access. The person is disabled rather than deleted, because they still
     * appear on every lease they signed.
     */
    @DELETE
    @Path("/staff/{userId}")
    public Response suspendStaff(@PathParam("userId") String userId) {
        staff.suspend(caller.requireAgencyId(), userId);
        return Response.noContent().build();
    }

    // ---------------------------------------------------- landlords and renters

    /**
     * Onboards a landlord, or links the one that already exists.
     *
     * <p>Returns their `partyId`, which is what goes in `landlordId` when the agency
     * lists a house for them.
     *
     * <p>If the address already belongs to somebody, this links rather than duplicates:
     * a landlord who places houses with two agencies is one person with one portfolio,
     * and a second account would silently split it.
     */
    @POST
    @Path("/landlords")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response onboardLandlord(@Valid OnboardPartyRequest request) {
        var provisioned = parties.onboard(
                caller.requireAgencyId(), request.email(),
                request.firstName(), request.lastName(), Roles.LANDLORD);
        return Response.status(provisioned.linked() ? Response.Status.OK : Response.Status.CREATED)
                .entity(ProvisionedResponse.from(provisioned))
                .build();
    }

    /**
     * The same for a renter, returning the `partyId` to use as `renterId` on a lease.
     */
    @POST
    @Path("/renters")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response onboardRenter(@Valid OnboardPartyRequest request) {
        var provisioned = parties.onboard(
                caller.requireAgencyId(), request.email(),
                request.firstName(), request.lastName(), Roles.RENTER);
        return Response.status(provisioned.linked() ? Response.Status.OK : Response.Status.CREATED)
                .entity(ProvisionedResponse.from(provisioned))
                .build();
    }
}
