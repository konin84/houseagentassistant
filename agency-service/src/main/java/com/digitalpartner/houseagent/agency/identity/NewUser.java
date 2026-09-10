package com.digitalpartner.houseagent.agency.identity;

/**
 * A person to be created.
 *
 * <p>Note that {@code agencyId} and {@code partyId} are set by the service rather than
 * taken from a request - see {@code StaffDirectory} and {@code PartyDirectory}. By the
 * time a value reaches this record it has already been decided, not accepted.
 *
 * @param password           the credential to set. Either one this service generated, or
 *                           one the person chose for themselves when signing up.
 * @param mustChangePassword true when somebody else picked the password, which is every
 *                           provisioned account: an agency admin who can read a
 *                           colleague's password should only be able to read one that
 *                           stops working the moment it is used. False when the person
 *                           chose it themselves, because there is nobody to lock out.
 */
public record NewUser(
        String email,
        String firstName,
        String lastName,
        String role,
        String agencyId,
        String partyId,
        String password,
        boolean mustChangePassword) {

    /** Provisioned by somebody else, so the credential is one-time. */
    public static NewUser provisioned(String email, String firstName, String lastName,
                                      String role, String agencyId, String partyId,
                                      String temporaryPassword) {
        return new NewUser(email, firstName, lastName, role, agencyId, partyId,
                temporaryPassword, true);
    }

    /** Signed up by the person themselves, who already knows their own password. */
    public static NewUser selfChosen(String email, String firstName, String lastName,
                                     String role, String agencyId, String password) {
        return new NewUser(email, firstName, lastName, role, agencyId, null,
                password, false);
    }
}
