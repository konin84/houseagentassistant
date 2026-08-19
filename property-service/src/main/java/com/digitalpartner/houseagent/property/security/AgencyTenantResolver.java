package com.digitalpartner.houseagent.property.security;

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
 * Supplies the agency discriminator Hibernate appends to every query against an
 * entity annotated with {@code @TenantId}.
 *
 * <p>Two entry points can establish it, checked in this order:
 *
 * <ol>
 *   <li>{@link AgencyContext} - set explicitly by Kafka consumers and scheduled jobs,
 *       which have no request and no token.</li>
 *   <li>The validated JWT of an in-flight HTTP request.</li>
 * </ol>
 *
 * <p>The agency is never read from a header, path or query parameter. Doing so would
 * let any caller impersonate any agency by editing a request, which is the whole
 * ballgame in a multi-tenant system.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class AgencyTenantResolver implements TenantResolver {

    /**
     * Sentinel used when no agency can be established: anonymous marketplace
     * visitors, and landlord or renter tokens.
     *
     * <p>It deliberately matches no real agency, so a tenant-scoped query made
     * without an agency returns nothing rather than everything. Failing closed matters
     * more here than failing helpfully.
     */
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
        // SecurityIdentity is request-scoped. Touching it from a consumer or scheduler
        // thread would throw rather than simply return nothing.
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
