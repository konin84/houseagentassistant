package com.digitalpartner.houseagent.notification.security;

import com.digitalpartner.houseagent.common.security.TokenClaims;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The caller's platform identity.
 *
 * <p>Only {@code party_id} matters in this service. There is no agency-scoped data
 * here at all, so unlike its siblings this class has no {@code requireAgencyId()} - a
 * method that existed but was never the basis of a decision would be an invitation to
 * start filtering by something that means nothing here.
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
     * Read from the token, never from the request. {@code GET /api/me/contact} must
     * mean the caller's own contact and nobody else's.
     */
    public java.util.UUID requirePartyId() {
        String partyId = claim(TokenClaims.PARTY_ID);
        if (partyId == null || partyId.isBlank()) {
            throw new ForbiddenException("Token carries no " + TokenClaims.PARTY_ID + " claim");
        }
        return java.util.UUID.fromString(partyId);
    }
}
