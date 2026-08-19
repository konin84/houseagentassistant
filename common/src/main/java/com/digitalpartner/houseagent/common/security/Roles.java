package com.digitalpartner.houseagent.common.security;

/**
 * Realm roles issued by Keycloak. Kept as constants so {@code @RolesAllowed} never
 * carries a raw string that a typo can silently turn into "deny everyone".
 */
public final class Roles {

    /** Operates the platform itself. Crosses agency boundaries by design. */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Owns one agency's account: billing, staff, settings. Scoped to that agency. */
    public static final String AGENCY_ADMIN = "AGENCY_ADMIN";

    /** Agency staff who list houses and manage leases. Scoped to their agency. */
    public static final String AGENT = "AGENT";

    /** Owns houses. Not agency-scoped: one landlord may work with several agencies. */
    public static final String LANDLORD = "LANDLORD";

    /** Rents a house and pays rent. Not agency-scoped, for the same reason. */
    public static final String RENTER = "RENTER";

    private Roles() {
    }
}
