package com.digitalpartner.houseagent.agency.api;

import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.SignupRequest;
import com.digitalpartner.houseagent.agency.api.dto.AgencyDtos.SignupResponse;
import com.digitalpartner.houseagent.agency.service.SelfSignup;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The one endpoint on this service that anybody may call.
 *
 * <p>Everything else here creates accounts on behalf of somebody who already has one.
 * This creates the first: an agency and the administrator who will run it, for a caller
 * with no token because they have nothing to get one with.
 *
 * <p>It is also the one endpoint on the platform where an unauthenticated request causes
 * an account to exist, so it is worth being clear about what stops that being abused.
 * Not much, inside this service. The name, city and email are whatever was typed; only
 * the parts that matter - the plan, the role and the agency id - are decided rather than
 * accepted. The rest is the gateway's rate limit, and a verification mail that this
 * platform does not send yet.
 *
 * <p>Note that Keycloak's own registration page stays disabled. It would produce a user
 * with no {@code agency_id} and no role - somebody who can obtain a valid token and is
 * refused by every endpoint on the platform. An agency and its administrator only make
 * sense created together, which is why signup is an API call and not a login-page link.
 */
@Path("/api/signup")
@Tag(name = "Signup", description = "Starting an agency, without an account to start it with")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class SignupResource {

    @Inject
    SelfSignup signup;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response signUp(@Valid SignupRequest request) {
        var result = signup.signUp(
                request.agencyName(), request.city(), request.countryCode(),
                request.contactPhone(), request.adminEmail(), request.password(),
                request.firstName(), request.lastName());

        return Response.status(Response.Status.CREATED)
                .entity(SignupResponse.from(result.agency(), result.administrator()))
                .build();
    }
}
