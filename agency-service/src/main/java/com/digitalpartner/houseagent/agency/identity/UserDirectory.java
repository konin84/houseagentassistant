package com.digitalpartner.houseagent.agency.identity;

import java.util.List;
import java.util.Optional;

/**
 * Everything this service needs from the identity provider, and nothing more.
 *
 * <h2>Why an interface rather than calling Keycloak directly</h2>
 *
 * The rules worth testing here are about <em>authorisation</em>: that an agency admin
 * cannot create staff for another agency, cannot mint another admin, and cannot turn a
 * rival's employee into their own landlord. None of that is about Keycloak, and none of
 * it should need Keycloak running to verify - the rest of this platform's tests need no
 * Docker, and provisioning is the last place to start requiring it.
 *
 * <p>So the tests bind an in-memory implementation and exercise the decisions. The
 * Keycloak implementation is verified separately, against a real realm.
 */
public interface UserDirectory {

    Optional<PlatformUser> findByEmail(String email);

    Optional<PlatformUser> findById(String userId);

    /** Everybody carrying this agency's id - the staff roster. */
    List<PlatformUser> findByAgency(String agencyId);

    PlatformUser create(NewUser user);

    /**
     * Adds a realm role to somebody who already exists.
     *
     * <p>Used when linking: a person who rents one house and owns another is one
     * account with both roles, not two accounts.
     */
    void grantRole(String userId, String role);

    /**
     * Disables rather than deletes.
     *
     * <p>A former agent still appears on the leases they signed. Removing the account
     * would leave those records pointing at nobody, so access is withdrawn and the
     * person remains explicable.
     */
    void setEnabled(String userId, boolean enabled);
}
