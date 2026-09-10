package com.digitalpartner.houseagent.agency.service;

import com.digitalpartner.houseagent.agency.domain.Agency;
import com.digitalpartner.houseagent.agency.domain.AgencyStatus;
import com.digitalpartner.houseagent.agency.domain.SubscriptionPlan;
import com.digitalpartner.houseagent.agency.outbox.OutboxWriter;
import com.digitalpartner.houseagent.common.events.AgencyEvents;
import com.digitalpartner.houseagent.agency.identity.NewUser;
import com.digitalpartner.houseagent.agency.identity.PlatformUser;
import com.digitalpartner.houseagent.agency.identity.UserDirectory;
import com.digitalpartner.houseagent.agency.service.StaffDirectory.Provisioned;
import com.digitalpartner.houseagent.common.security.Roles;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * The agencies themselves. Platform-admin work.
 *
 * <h2>Why creating an agency also creates a person</h2>
 *
 * An agency with no administrator can do nothing - it cannot add its own first admin,
 * because adding staff requires being one. Somebody has to break that circle from
 * outside, and the platform admin is the only role that stands outside every agency.
 *
 * <p>So the two happen together: the agency record and the account that can then run
 * it. Splitting them into two calls would leave an agency stranded whenever the second
 * call failed.
 */
@ApplicationScoped
public class AgencyRegistry {

    private static final Logger LOG = Logger.getLogger(AgencyRegistry.class);

    @Inject
    UserDirectory users;

    @Inject
    TemporaryPasswords passwords;

    @Inject
    OutboxWriter outbox;

    @Inject
    PhoneIdentifiers phones;

    @Transactional
    public Agency register(String agencyId, String name, String city, String countryCode,
                           String contactEmail, String contactPhone) {
        if (Agency.findById(agencyId) != null) {
            throw new AgencyAlreadyExistsException(agencyId);
        }

        Agency agency = new Agency();
        agency.agencyId = agencyId;
        agency.name = name;
        agency.city = city;
        agency.countryCode = countryCode;
        agency.contactEmail = contactEmail;
        agency.contactPhone = contactPhone;
        agency.status = AgencyStatus.ACTIVE;
        // Everybody starts free. An agency that has never been given a plan is not a
        // special case to handle later; it is simply on the free one.
        agency.plan = SubscriptionPlan.FREE;
        agency.planChangedAt = Instant.now();
        agency.persist();

        // Announced at creation as well as on change, so property-service never has to
        // guess what a brand new agency is allowed.
        announcePlan(agency);

        LOG.infof("Registered agency %s (%s) on %s", agencyId, name, agency.plan);
        return agency;
    }

    /**
     * The first administrator, created outside the agency by somebody who stands
     * outside every agency.
     *
     * <p>Not transactional with {@link #register}: the account lives in Keycloak, and
     * no database transaction can roll that back. Creating the agency first means the
     * failure mode is an agency briefly without an admin - recoverable by calling this
     * again - rather than an admin belonging to an agency that does not exist.
     */
    public Provisioned addFirstAdmin(String agencyId, String email, String firstName,
                                     String lastName, String phone) {
        if (find(agencyId) == null) {
            throw new AgencyNotFoundException(agencyId);
        }
        users.findByEmail(email).ifPresent(existing -> {
            throw new AlreadyRegisteredException("That email address already belongs to an account");
        });

        String number = phones.claim(phone);

        String temporary = passwords.generate();
        PlatformUser created = users.create(NewUser.provisioned(
                email.trim(), firstName, lastName, Roles.AGENCY_ADMIN, agencyId, null,
                number, temporary));

        LOG.infof("Agency %s given its first admin, %s", agencyId, email);
        return new Provisioned(created, temporary, false);
    }

    /**
     * Moves an agency to a different plan.
     *
     * <p>Platform work, not the agency's own. Without payment behind it, an agency
     * admin able to call this could award themselves the unlimited tier - so the
     * decision sits with whoever is doing the billing until there is billing to do it.
     *
     * <p>Note what does <em>not</em> happen on a downgrade: nothing. An agency holding
     * twenty-five houses that moves to the free five keeps all twenty-five, and is
     * simply refused the twenty-sixth. Hiding the excess would take a landlord's
     * advertisement off the market over a decision their agency made about billing, and
     * they never agreed to anything.
     */
    @Transactional
    public Agency changePlan(String agencyId, SubscriptionPlan plan) {
        Agency agency = require(agencyId);
        SubscriptionPlan previous = agency.plan;

        agency.plan = plan;
        agency.planChangedAt = Instant.now();
        agency.updatedAt = Instant.now();

        announcePlan(agency);

        LOG.infof("Agency %s moved from %s to %s", agencyId, previous, plan);
        return agency;
    }

    /**
     * Writes the plan to the outbox, inside the caller's transaction.
     *
     * <p>The ceiling is resolved here rather than left to the consumer. property-service
     * enforces the limit but has no business knowing what a STARTER plan is worth -
     * that is a commercial fact, and one that should change in one place.
     */
    private void announcePlan(Agency agency) {
        outbox.record("agency", agency.agencyId, agency.agencyId, "AgencyPlanChanged",
                new AgencyEvents.AgencyPlanChanged(
                        agency.agencyId,
                        agency.plan.name(),
                        agency.plan.maxHouses(),
                        Instant.now()));
    }

    @Transactional
    public Agency find(String agencyId) {
        return Agency.findById(agencyId);
    }

    @Transactional
    public Agency require(String agencyId) {
        Agency agency = Agency.findById(agencyId);
        if (agency == null) {
            throw new AgencyNotFoundException(agencyId);
        }
        return agency;
    }

    /**
     * Removes an agency created moments ago whose administrator could not be created.
     *
     * <p>The only deletion in this service, and deliberately not exposed anywhere. An
     * agency that has operated for a day has houses, leases and money attached to it in
     * three other services, none of which would hear about this - which is why suspending
     * is the answer everywhere else. The one safe case is the one this exists for: an
     * agency seconds old that nobody has ever been able to act for.
     */
    @Transactional
    public void deleteAbandoned(String agencyId) {
        Agency.deleteById(agencyId);
    }

    @Transactional
    public List<Agency> all() {
        return Agency.listAll(Sort.by("name"));
    }

    @Transactional
    public Agency setStatus(String agencyId, AgencyStatus status) {
        Agency agency = require(agencyId);
        agency.status = status;
        agency.updatedAt = Instant.now();
        return agency;
    }

    /** An agency editing its own details. The id and status are not theirs to change. */
    @Transactional
    public Agency updateOwnProfile(String agencyId, String name, String city,
                                   String countryCode, String contactEmail,
                                   String contactPhone) {
        Agency agency = require(agencyId);
        if (name != null) {
            agency.name = name;
        }
        if (city != null) {
            agency.city = city;
        }
        if (countryCode != null) {
            agency.countryCode = countryCode;
        }
        if (contactEmail != null) {
            agency.contactEmail = contactEmail;
        }
        if (contactPhone != null) {
            agency.contactPhone = contactPhone;
        }
        agency.updatedAt = Instant.now();
        return agency;
    }
}
