package com.digitalpartner.houseagent.agency.identity;

import java.util.Set;

/**
 * A person as the identity provider knows them.
 *
 * @param agencyId the agency they are staff of, or null. Its presence is what
 *                 distinguishes agency staff from a landlord or renter, and is checked
 *                 before this platform will treat somebody as either.
 * @param partyId  their platform-wide id, or null for staff. This is what a lease and
 *                 an invoice point at.
 * @param phone    normalised, or null if they gave none. When present it is also their
 *                 {@code username}, which is what makes signing in with it work - see
 *                 {@code KeycloakUserDirectory}.
 */
public record PlatformUser(
        String userId,
        String username,
        String email,
        String firstName,
        String lastName,
        Set<String> roles,
        String agencyId,
        String partyId,
        String phone,
        boolean enabled) {

    public boolean isAgencyStaff() {
        return agencyId != null && !agencyId.isBlank();
    }

    public boolean has(String role) {
        return roles.contains(role);
    }
}
