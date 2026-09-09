package com.digitalpartner.houseagent.agency.security;

import com.digitalpartner.houseagent.common.security.TokenClaims;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Who is asking.
 *
 * <p>{@link #requireAgencyId()} matters more here than anywhere else on the platform.
 * Elsewhere it decides which rows you may read; here it decides which agency a new
 * member of staff is stamped with - and a value taken from a request body rather than a
 * token would let one agency admin quietly put an employee inside a competitor.
 */
@RequestScoped
public class CallerContext {

    @Inject
    SecurityIdentity identity;

    private String claim(String name) {
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            return jwt.getClaim(name);
        }
        Object attribute = identity.getAttribute(name);
        return attribute == null ? null : attribute.toString();
    }

    /**
     * The agency the caller administers.
     *
     * @throws ForbiddenException when the token carries no agency - a platform admin,
     *                            a landlord or a renter. Note that this is why
     *                            PLATFORM_ADMIN cannot use the agency endpoints even
     *                            though it is the most privileged role: it administers
     *                            the platform, not any particular agency.
     */
    public String requireAgencyId() {
        String agencyId = claim(TokenClaims.AGENCY_ID);
        if (agencyId == null || agencyId.isBlank()) {
            throw new ForbiddenException("Token carries no " + TokenClaims.AGENCY_ID + " claim");
        }
        return agencyId;
    }

    /** Used only for logging who provisioned whom. */
    public String callerName() {
        return identity.getPrincipal() == null ? "unknown" : identity.getPrincipal().getName();
    }
}
