package com.digitalpartner.houseagent.agency.service;

import com.digitalpartner.houseagent.agency.identity.NewUser;
import com.digitalpartner.houseagent.agency.identity.PlatformUser;
import com.digitalpartner.houseagent.agency.identity.UserDirectory;
import com.digitalpartner.houseagent.agency.service.StaffDirectory.Provisioned;
import com.digitalpartner.houseagent.common.security.Roles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Onboarding the landlords and renters an agency works with.
 *
 * <h2>One person, one party_id, however many agencies</h2>
 *
 * A landlord may place one house with agency A and another with agency B. The platform
 * is built on that being <em>one</em> person: {@code landlord_lease_view} carries no
 * tenant filter precisely so a portfolio spans agencies, and the same is true of a
 * renter's invoices.
 *
 * <p>So the second agency to onboard somebody must <em>link</em> to the account that
 * exists rather than create another. Creating a second {@code party_id} would silently
 * split their leases and earnings across two accounts they could never see together,
 * and nothing would fail - it would simply look, to them, as though half their
 * properties had disappeared.
 *
 * <p>The address is the identity. Two people with one email is not a case this platform
 * needs to support; one person with two accounts is a case it must not create.
 */
@ApplicationScoped
public class PartyDirectory {

    private static final Logger LOG = Logger.getLogger(PartyDirectory.class);

    private static final Set<String> ONBOARDABLE = Set.of(Roles.LANDLORD, Roles.RENTER);

    @Inject
    UserDirectory users;

    @Inject
    TemporaryPasswords passwords;

    /**
     * Finds the person, or creates them.
     *
     * @return the account and its {@code party_id} - which is what the caller then uses
     *         as {@code landlordId} on a house or {@code renterId} on a lease
     */
    public Provisioned onboard(String agencyId, String email, String firstName,
                               String lastName, String role) {
        if (!ONBOARDABLE.contains(role)) {
            throw new RoleNotGrantableException(
                    "Only " + String.join(" and ", ONBOARDABLE) + " can be onboarded here; "
                            + role + " is agency staff");
        }

        var existing = users.findByEmail(email.trim());
        if (existing.isPresent()) {
            return link(agencyId, existing.get(), role);
        }

        String temporary = passwords.generate();
        PlatformUser created = users.create(NewUser.provisioned(
                email.trim(), firstName, lastName, role,
                // No agency: landlords and renters are platform-wide principals, and an
                // agency_id on one would make the tenant filter hide their own data
                // from them the moment they dealt with a second agency.
                null,
                UUID.randomUUID().toString(),
                temporary));

        LOG.infof("Agency %s onboarded new %s %s", agencyId, role, email);
        return new Provisioned(created, temporary, false);
    }

    /**
     * Attaches to an account that already exists.
     *
     * <p>No password is returned: this person already has one, and handing an agency a
     * fresh credential for somebody else's existing account would be a takeover rather
     * than an introduction.
     */
    private Provisioned link(String agencyId, PlatformUser existing, String role) {
        if (existing.isAgencyStaff()) {
            // An agent of another agency is not available to be adopted as a landlord.
            // Allowing it would let one agency add roles to a competitor's employee,
            // using nothing but a guessed work address.
            throw new AlreadyRegisteredException(
                    "That email address belongs to agency staff and cannot be onboarded "
                            + "as a " + role);
        }

        if (existing.has(role)) {
            LOG.infof("Agency %s linked existing %s %s", agencyId, role, existing.email());
            return new Provisioned(existing, null, true);
        }

        // Somebody may genuinely be both: renting a flat in town and letting out a
        // house they inherited. One account, two roles.
        users.grantRole(existing.userId(), role);
        LOG.infof("Agency %s linked existing account %s and granted %s",
                agencyId, existing.email(), role);

        // Re-read rather than returning the copy fetched before the grant. That copy
        // still carries the old roles, so the response would omit the very role this
        // call just added - and a caller checking what it got back would conclude the
        // request had not worked.
        return new Provisioned(
                users.findById(existing.userId()).orElse(existing), null, true);
    }
}
