package com.digitalpartner.houseagent.lease.security;

import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.common.security.TokenClaims;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.tenant.TenantResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Supplies the agency discriminator for this service's tenant-scoped entities.
 *
 * <p>Intentionally duplicated from property-service rather than shared. Isolation is
 * the security boundary of the platform, and a change to how one service resolves its
 * tenant should never silently alter another's. The forty lines are cheaper than that
 * coupling.
 *
 * <p>Note that {@code OutboxEvent} and {@code LandlordLeaseView} carry no
 * {@code @TenantId} and are therefore untouched by this resolver - the relay must see
 * every agency's events, and landlords are not agency-scoped at all.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class AgencyTenantResolver implements TenantResolver {

    /** Matches no real agency, so an unresolved tenant returns nothing, not everything. */
    public static final String NO_AGENCY = "__none__";

    /**
     * The identity rather than the token: {@code JsonWebToken} is only a bean when OIDC
     * is enabled, and injecting it directly stops the service starting wherever
     * authentication is switched off. In production the principal is the JWT, so the
     * resolved tenant is unchanged.
     */
    @Inject
    SecurityIdentity identity;

    @Override
    public String getDefaultTenantId() {
        return NO_AGENCY;
    }

    @Override
    public String resolveTenantId() {
        String explicit = AgencyContext.current();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (!Arc.container().requestContext().isActive()) {
            return NO_AGENCY;
        }
        String agencyId = identity.getPrincipal() instanceof JsonWebToken jwt
                ? jwt.getClaim(TokenClaims.AGENCY_ID)
                : asString(identity.getAttribute(TokenClaims.AGENCY_ID));
        return (agencyId == null || agencyId.isBlank()) ? NO_AGENCY : agencyId;
    }

    private static String asString(Object attribute) {
        return attribute == null ? null : attribute.toString();
    }
}
