package com.digitalpartner.houseagent.common.security;

/**
 * Custom JWT claims this platform relies on. Both are minted by Keycloak via
 * protocol mappers; neither is ever accepted from a request body or query string.
 */
public final class TokenClaims {

    /**
     * Identifies the SaaS tenant - one rental agency. Present for AGENCY_ADMIN and
     * AGENT tokens, absent for LANDLORD and RENTER, who are platform-wide principals
     * that may deal with several agencies over time.
     * <p>
     * This is the value the Hibernate tenant filter keys on.
     */
    public static final String AGENCY_ID = "agency_id";

    /**
     * Stable platform-wide identifier of the authenticated person, independent of
     * which agency they are currently dealing with.
     * <p>
     * This is what "show me my own payment history" filters on. Using the Keycloak
     * subject directly would work too, but a dedicated claim survives a move to a
     * different identity provider.
     */
    public static final String PARTY_ID = "party_id";

    private TokenClaims() {
    }
}
