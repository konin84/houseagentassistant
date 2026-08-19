package com.digitalpartner.houseagent.common.devsupport;

import com.digitalpartner.houseagent.common.security.TokenClaims;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Map;

/**
 * Lets a developer act as an agent, a landlord or a renter without a Keycloak realm.
 *
 * <h2>This reads identity from request headers, which is the thing this platform
 * otherwise never does</h2>
 *
 * Every other class here is emphatic that the agency comes from a validated token and
 * never from something a caller can edit, because a header-derived tenant is a
 * one-request impersonation of any customer. That reasoning is not softened here; it is
 * the reason this class carries {@link IfBuildProfile @IfBuildProfile("dev")}.
 *
 * <p>That annotation is a build-time condition, not a runtime flag. In a production
 * build this bean is not registered, not reachable, and not something a misconfigured
 * environment variable can switch back on. The safety comes from the class being absent
 * rather than from it behaving well.
 *
 * <p>It is also strictly a fallback. Dev mode now authenticates against the local
 * Keycloak realm like every other environment, and a request carrying a real token is
 * left completely alone - see the first check in
 * {@link #augment(SecurityIdentity, AuthenticationRequestContext, Map)}. These headers
 * only fill a vacuum, which is what lets a service be run and poked at with no Keycloak
 * and no Docker at all.
 *
 * <h2>Using it</h2>
 *
 * <pre>
 * X-Dev-Roles:  AGENT            required - without it the request stays anonymous
 * X-Dev-Agency: agency-a         becomes the agency_id claim
 * X-Dev-Party:  {uuid}           becomes the party_id claim
 * </pre>
 *
 * <p>Send no headers and nothing is augmented, so the public marketplace can still be
 * exercised as an anonymous visitor.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevIdentityAugmentor implements SecurityIdentityAugmentor {

    public static final String ROLES_HEADER = "X-Dev-Roles";
    public static final String AGENCY_HEADER = "X-Dev-Agency";
    public static final String PARTY_HEADER = "X-Dev-Party";

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {
        // Only the attribute-carrying overload can reach the request, so this one is
        // never the useful path. Returning the identity unchanged is correct rather
        // than merely safe.
        return Uni.createFrom().item(identity);
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context,
                                         Map<String, Object> attributes) {
        // A real token wins, always. Once Keycloak has authenticated somebody, their
        // identity is the answer and a header must not be able to quietly replace it -
        // otherwise a request could act as someone other than its token says, which is
        // exactly the confusion this shim exists to avoid rather than create.
        if (!identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        RoutingContext routing = HttpSecurityUtils.getRoutingContextAttribute(attributes);
        if (routing == null) {
            return Uni.createFrom().item(identity);
        }

        String roles = routing.request().getHeader(ROLES_HEADER);
        if (roles == null || roles.isBlank()) {
            // No claim to any role, so no identity is minted. This is what keeps the
            // anonymous marketplace anonymous.
            return Uni.createFrom().item(identity);
        }

        String agencyId = routing.request().getHeader(AGENCY_HEADER);
        String partyId = routing.request().getHeader(PARTY_HEADER);

        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity)
                // A principal is what makes the identity non-anonymous, which
                // @RolesAllowed requires before it will even look at the roles.
                .setPrincipal(new QuarkusPrincipal(
                        partyId != null ? partyId : "dev-" + roles.toLowerCase()))
                .setAnonymous(false);

        Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .forEach(builder::addRole);

        // Read back by CallerContext and AgencyTenantResolver, which look at the
        // principal's claims first and fall back to these attributes.
        if (agencyId != null && !agencyId.isBlank()) {
            builder.addAttribute(TokenClaims.AGENCY_ID, agencyId);
        }
        if (partyId != null && !partyId.isBlank()) {
            builder.addAttribute(TokenClaims.PARTY_ID, partyId);
        }

        return Uni.createFrom().item(builder.build());
    }
}
