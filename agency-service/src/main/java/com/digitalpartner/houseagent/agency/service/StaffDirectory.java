package com.digitalpartner.houseagent.agency.service;

import com.digitalpartner.houseagent.agency.identity.NewUser;
import com.digitalpartner.houseagent.agency.identity.PlatformUser;
import com.digitalpartner.houseagent.agency.identity.UserDirectory;
import com.digitalpartner.houseagent.common.security.Roles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Adding and removing an agency's own staff.
 *
 * <h2>The two rules that matter</h2>
 *
 * <b>The agency comes from the caller's token.</b> There is no parameter for it and
 * there never should be: an agency admin who could name the agency would be able to
 * place an employee inside a competitor, and that employee's token would then carry the
 * competitor's {@code agency_id} - which is the value every other service filters its
 * data by. One request would defeat the entire tenancy model.
 *
 * <p><b>An admin cannot create an admin.</b> Only {@code AGENT} can be provisioned
 * here. A role that can grant itself stops being a boundary: one compromised admin
 * account would otherwise become as many as the attacker liked, and removing the
 * original would not help.
 */
@ApplicationScoped
public class StaffDirectory {

    private static final Logger LOG = Logger.getLogger(StaffDirectory.class);

    /**
     * What an agency admin may hand out.
     *
     * <p>Deliberately not AGENCY_ADMIN and emphatically not PLATFORM_ADMIN. A second
     * admin for an agency is a platform-admin operation, because it is the one action
     * whose blast radius is the agency itself.
     */
    private static final Set<String> GRANTABLE = Set.of(Roles.AGENT);

    @Inject
    UserDirectory users;

    @Inject
    TemporaryPasswords passwords;

    /**
     * Creates a member of staff in the caller's own agency.
     *
     * @param agencyId taken from the caller's token by the resource, never from the body
     * @return the new user, and the one-time password to hand over
     */
    public Provisioned addStaff(String agencyId, String email, String firstName,
                                String lastName, String role) {
        if (!GRANTABLE.contains(role)) {
            throw new RoleNotGrantableException(
                    "An agency admin may create " + String.join(", ", GRANTABLE)
                            + " only; " + role + " has to come from a platform admin");
        }

        users.findByEmail(email).ifPresent(existing -> {
            // Not "already in your agency" - that would confirm whether an address
            // belongs to a competitor's employee.
            throw new AlreadyRegisteredException(
                    "That email address already belongs to an account");
        });

        String temporary = passwords.generate();
        PlatformUser created = users.create(new NewUser(
                email.trim(), firstName, lastName, role,
                // The agency is the caller's own, decided here rather than accepted.
                agencyId,
                // Staff have no party_id. They act for an agency, and are not
                // themselves a landlord or a renter of anything.
                null,
                temporary));

        LOG.infof("Agency %s added %s as %s", agencyId, email, role);
        return new Provisioned(created, temporary, false);
    }

    /** The agency's roster, read from the identity provider rather than a local copy. */
    public List<PlatformUser> staffOf(String agencyId) {
        return users.findByAgency(agencyId).stream()
                .filter(PlatformUser::isAgencyStaff)
                .toList();
    }

    /**
     * Withdraws access without deleting the person.
     *
     * <p>Checks the target is in the caller's own agency first. Without that, an admin
     * holding any user id could disable an employee of another agency - a denial of
     * service against a competitor, delivered by an id they guessed.
     */
    public void suspend(String agencyId, String userId) {
        PlatformUser target = users.findById(userId)
                .orElseThrow(() -> new StaffNotFoundException(userId));

        if (!agencyId.equals(target.agencyId())) {
            // The same 404 as a user who does not exist. A different answer here would
            // confirm that the id belongs to somebody.
            throw new StaffNotFoundException(userId);
        }

        users.setEnabled(userId, false);
        LOG.infof("Agency %s suspended %s", agencyId, target.email());
    }

    /** A newly provisioned account, plus the credential to pass on. */
    public record Provisioned(PlatformUser user, String temporaryPassword, boolean linked) {
    }
}
