package com.digitalpartner.houseagent.agency.service;

import com.digitalpartner.houseagent.agency.domain.Agency;
import com.digitalpartner.houseagent.agency.identity.NewUser;
import com.digitalpartner.houseagent.agency.identity.PlatformUser;
import com.digitalpartner.houseagent.agency.identity.UserDirectory;
import com.digitalpartner.houseagent.common.security.Roles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * An agency signing itself up, with nobody at the platform involved.
 *
 * <h2>What this changes about the circle</h2>
 *
 * An agency with no administrator can do nothing, and adding staff requires already
 * being one - so somebody has to break that circle from outside. Until now that was the
 * platform admin. Now it can also be the person starting the agency: they arrive with
 * neither an account nor an agency, and this creates both in one step, which is the only
 * way to create either without already having the other.
 *
 * <p>{@link AgencyRegistry#addFirstAdmin} remains, and is not redundant. It is how an
 * agency that has lost its administrator gets another, and how one created by the
 * platform on somebody's behalf gets its first.
 *
 * <h2>The three things a stranger does not get to decide</h2>
 *
 * <b>The plan.</b> Always {@code FREE}. An anonymous caller able to name a tier would
 * take the unlimited one, and there is no payment behind any of this yet.
 *
 * <p><b>The role.</b> Always {@code AGENCY_ADMIN}, written here as a constant rather
 * than read from anything. The role a signup form could influence is the role an
 * attacker picks, and {@code PLATFORM_ADMIN} is one of the values that would then be
 * available.
 *
 * <p><b>The agency id.</b> Derived from the name - see {@link AgencySlugs}.
 *
 * <h2>Proving the address</h2>
 *
 * The account is created but cannot be signed into until the person clicks the link
 * Keycloak mails them. That is what stops somebody signing up as
 * {@code contact@a-real-agency.example} and holding a real business's name hostage:
 * they can create the row, and they can never use it.
 *
 * <p>Agency admins are the only people on the platform who verify - see
 * {@link com.digitalpartner.houseagent.agency.identity.NewUser#requiresEmailVerification}.
 * Everybody else was typed in by somebody accountable, and making them all check their
 * mail would be friction spent where nothing was at risk.
 *
 * <p>The row still exists in the meantime, holding its id, which is the remaining rough
 * edge: an unverified signup can take a name and never come back. Nothing acts on that
 * yet. A sweep of agencies whose administrator never verified would be the fix, and it
 * needs an answer to how long is long enough before deleting somebody's agency.
 */
@ApplicationScoped
public class SelfSignup {

    private static final Logger LOG = Logger.getLogger(SelfSignup.class);

    /**
     * How many ids to try before giving up.
     *
     * <p>The first four are readable variations on the name; after that they carry
     * randomness, so exhausting even this many means something other than a popular
     * agency name is going on.
     */
    private static final int MAX_ATTEMPTS = 8;

    @Inject
    AgencyRegistry agencies;

    @Inject
    UserDirectory users;

    @Inject
    PhoneIdentifiers phones;

    public SignedUp signUp(String agencyName, String city, String countryCode,
                           String contactPhone, String email, String password,
                           String firstName, String lastName, String adminPhone) {

        String address = email.trim();

        // Asked before anything is written. An address already in use means this person
        // has an account - possibly as a landlord with another agency - and the answer
        // is to sign in, not to acquire a second identity.
        users.findByEmail(address).ifPresent(existing -> {
            throw new AlreadyRegisteredException(
                    "That email address already belongs to an account. Sign in instead.");
        });

        // Both checks before anything is written, so a taken number cannot leave an
        // agency behind the way a failed account creation would.
        String number = phones.claim(adminPhone);

        Agency agency = registerUnderAFreeId(agencyName, city, countryCode, address,
                contactPhone);

        PlatformUser admin;
        try {
            admin = users.create(NewUser.selfChosen(
                    address, firstName, lastName,
                    // Not a parameter, and not derived from anything the caller sent.
                    Roles.AGENCY_ADMIN,
                    agency.agencyId,
                    number,
                    password));
        } catch (RuntimeException e) {
            // The agency exists and its administrator does not, which is an agency
            // nobody can ever act for - and it has taken a name. It is empty and
            // seconds old, so removing it is safe in a way it will never be again.
            //
            // What this does not undo is the plan announcement already in the outbox.
            // property-service will learn the ceiling of an agency that no longer
            // exists, and hold a row nothing will ever query. Harmless, and much
            // cheaper than a distributed transaction over an identity provider.
            LOG.errorf(e, "Signup failed after creating agency %s; removing it",
                    agency.agencyId);
            agencies.deleteAbandoned(agency.agencyId);
            throw e;
        }

        LOG.infof("Agency %s signed itself up, administered by %s, on %s",
                agency.agencyId, address, agency.plan);
        return new SignedUp(agency, admin);
    }

    /**
     * Creates the agency under the first id its name is not already using.
     *
     * <p>A loop rather than a query for the next free slug, because a query would answer
     * a question that stops being true before it is used. The insert is the only thing
     * that actually decides, so the insert is what is retried.
     */
    private Agency registerUnderAFreeId(String name, String city, String countryCode,
                                        String contactEmail, String contactPhone) {
        String base = AgencySlugs.from(name);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = attempt == 0 ? base : AgencySlugs.alternative(base, attempt);
            try {
                // FREE is not passed in. register() puts every new agency there, so
                // there is no argument on this path that could carry a different tier.
                return agencies.register(candidate, name, city, countryCode,
                        contactEmail, contactPhone);
            } catch (AgencyAlreadyExistsException taken) {
                LOG.debugf("Agency id %s is taken; trying another", candidate);
            }
        }
        throw new SignupFailedException(
                "Could not find an unused id for an agency named '" + name + "'");
    }

    /** A brand new agency and the person who may now act for it. */
    public record SignedUp(Agency agency, PlatformUser administrator) {
    }
}
