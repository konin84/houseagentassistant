package com.digitalpartner.houseagent.agency.service;

import com.digitalpartner.houseagent.agency.domain.Agency;
import com.digitalpartner.houseagent.agency.domain.AgencyStatus;
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
        agency.persist();

        LOG.infof("Registered agency %s (%s)", agencyId, name);
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
                                     String lastName) {
        if (find(agencyId) == null) {
            throw new AgencyNotFoundException(agencyId);
        }
        users.findByEmail(email).ifPresent(existing -> {
            throw new AlreadyRegisteredException("That email address already belongs to an account");
        });

        String temporary = passwords.generate();
        PlatformUser created = users.create(new NewUser(
                email.trim(), firstName, lastName, Roles.AGENCY_ADMIN, agencyId, null, temporary));

        LOG.infof("Agency %s given its first admin, %s", agencyId, email);
        return new Provisioned(created, temporary, false);
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
