package com.digitalpartner.houseagent.lease.security;

import com.digitalpartner.houseagent.common.security.TokenClaims;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

/**
 * Typed access to the two claims this platform makes authorisation decisions with.
 *
 * <p>{@link #requirePartyId()} is load-bearing here in a way it is not in
 * property-service: it is the only thing scoping a landlord to their own tenancies,
 * because landlords are platform-wide principals that the tenant filter cannot help
 * with.
 */
@RequestScoped
public class CallerContext {

    /**
     * The identity, rather than the token.
     *
     * <p>{@code JsonWebToken} is only a bean when OIDC is switched on, so injecting it
     * directly makes the whole service fail to start wherever authentication is
     * disabled - which is exactly where a developer wants to run it. SecurityIdentity
     * always exists, and in production its principal <em>is</em> the JWT, so nothing
     * about the deployed behaviour changes.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * A claim from the caller's token, or from whatever established the identity.
     *
     * <p>In production that is always the validated JWT. In dev mode there is no token
     * and the attributes come from {@code DevIdentityAugmentor} instead - which is
     * compiled into dev builds only.
     */
    private String claim(String name) {
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            return jwt.getClaim(name);
        }
        Object attribute = identity.getAttribute(name);
        return attribute == null ? null : attribute.toString();
    }

    /**
     * The agency the caller acts for.
     *
     * @throws ForbiddenException when an agency-only endpoint is reached by a landlord
     *                            or renter token, which carries no agency claim
     */
    public String requireAgencyId() {
        String agencyId = claim(TokenClaims.AGENCY_ID);
        if (agencyId == null || agencyId.isBlank()) {
            throw new ForbiddenException("Token carries no " + TokenClaims.AGENCY_ID + " claim");
        }
        return agencyId;
    }

    /**
     * The platform-wide identity of the caller.
     *
     * <p>Read from the token and never from the request. {@code GET
     * /api/landlord/leases?landlordId=...} would let any landlord read every other
     * landlord's tenancies by changing one query parameter.
     */
    public UUID requirePartyId() {
        String partyId = claim(TokenClaims.PARTY_ID);
        if (partyId == null || partyId.isBlank()) {
            throw new ForbiddenException("Token carries no " + TokenClaims.PARTY_ID + " claim");
        }
        return UUID.fromString(partyId);
    }
}
