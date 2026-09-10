package com.digitalpartner.houseagent.agency.identity;

import com.digitalpartner.houseagent.common.security.Roles;

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
        /** Already normalised by the service. Becomes the username when present. */
        String phone,
        String password,
        boolean mustChangePassword) {

    /**
     * Whether this person has to prove they own the address before they can sign in.
     *
     * <h2>Why only agency admins</h2>
     *
     * Verification is friction, and friction stops people using a platform. It is worth
     * spending where an unproved address actually costs something, and nowhere else.
     *
     * <p>An agency admin is the one account created from an open, unauthenticated
     * request. Nobody vouches for the address, which is what makes squatting possible:
     * sign up as {@code contact@a-real-agency.example} and the real business finds its
     * own name taken by somebody it has never met. It is also the most powerful account
     * inside a tenancy - it hires staff and onboards the landlords and renters whose
     * money moves through the platform.
     *
     * <p>Everybody else was typed in by somebody accountable. An agency admin sitting in
     * their office adding an agent, a landlord or a renter has a relationship with that
     * person; a wrong address there is a mistake to correct, not an attack. Making those
     * three verify would mean an agency cannot onboard a landlord who is standing in
     * front of them until that landlord goes home and checks their mail, which is a
     * worse platform in exchange for a risk nobody was running.
     *
     * <p>Note this is a property of the <em>role</em> rather than of how the account was
     * created, so the platform-admin path gets it too. One rule, in one place, that
     * cannot drift between call sites - and an agency admin created on somebody's behalf
     * is no less powerful than one who signed themselves up.
     */
    public boolean requiresEmailVerification() {
        return Roles.AGENCY_ADMIN.equals(role);
    }

    /** Provisioned by somebody else, so the credential is one-time. */
    public static NewUser provisioned(String email, String firstName, String lastName,
                                      String role, String agencyId, String partyId,
                                      String phone, String temporaryPassword) {
        return new NewUser(email, firstName, lastName, role, agencyId, partyId, phone,
                temporaryPassword, true);
    }

    /** Signed up by the person themselves, who already knows their own password. */
    public static NewUser selfChosen(String email, String firstName, String lastName,
                                     String role, String agencyId, String phone,
                                     String password) {
        return new NewUser(email, firstName, lastName, role, agencyId, null, phone,
                password, false);
    }
}
