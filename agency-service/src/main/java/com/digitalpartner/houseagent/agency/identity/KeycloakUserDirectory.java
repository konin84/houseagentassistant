package com.digitalpartner.houseagent.agency.identity;

import com.digitalpartner.houseagent.common.security.TokenClaims;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The real directory: Keycloak, reached with this service's own service account.
 *
 * <p>A service account rather than the calling admin's token, because provisioning is
 * done on behalf of the platform. It is scoped to {@code manage-users},
 * {@code view-users} and {@code query-users} and nothing else - it cannot read a client
 * secret or create a role, so a leaked secret cannot grant anybody a privilege the
 * platform does not already hand out.
 *
 * <p>Registered only when provisioning is configured. Tests switch that off and bind
 * an in-memory implementation instead - without the condition this class would still
 * declare an injection point for a {@code Keycloak} bean that does not exist, and the
 * whole service would fail to start rather than simply having no directory.
 */
@ApplicationScoped
@IfBuildProperty(name = "app.provisioning.keycloak", stringValue = "true")
public class KeycloakUserDirectory implements UserDirectory {

    private static final Logger LOG = Logger.getLogger(KeycloakUserDirectory.class);

    /** Keycloak's own name for it. Not an enum in the admin client. */
    private static final String VERIFY_EMAIL = "VERIFY_EMAIL";

    @Inject
    Keycloak keycloak;

    @ConfigProperty(name = "app.keycloak.realm", defaultValue = "houseagent")
    String realmName;

    private RealmResource realm() {
        return keycloak.realm(realmName);
    }

    @Override
    public Optional<PlatformUser> findByEmail(String email) {
        // exact = true: a substring match would let one agency discover accounts by
        // typing a fragment, and would link the wrong person on a near miss.
        List<UserRepresentation> found = realm().users().searchByEmail(email.trim(), true);
        return found.isEmpty() ? Optional.empty() : Optional.of(toPlatformUser(found.getFirst()));
    }

    @Override
    public Optional<PlatformUser> findById(String userId) {
        try {
            return Optional.of(toPlatformUser(realm().users().get(userId).toRepresentation()));
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<PlatformUser> findByAgency(String agencyId) {
        // Keycloak can search by attribute, which keeps the roster a query rather than
        // a table this service would have to keep in step.
        return realm().users().searchByAttributes(TokenClaims.AGENCY_ID + ":" + agencyId)
                .stream()
                .map(this::toPlatformUser)
                .toList();
    }

    @Override
    public PlatformUser create(NewUser user) {
        UserRepresentation representation = new UserRepresentation();
        // Username is the email. One less thing for a person to remember, and it makes
        // "already exists" mean the same thing on both fields.
        representation.setUsername(user.email());
        representation.setEmail(user.email());
        representation.setFirstName(user.firstName());
        representation.setLastName(user.lastName());
        representation.setEnabled(true);
        // Not verified: nobody has proved they own this address yet. Somebody typed it -
        // an agency onboarding a landlord, or a stranger signing up.
        representation.setEmailVerified(false);

        // Agency admins have to prove it before they can sign in; everybody else does
        // not - see NewUser.requiresEmailVerification for why the line is drawn there.
        //
        // Per user rather than the realm's own verifyEmail flag, which is all-or-
        // nothing and would make a landlord check their mail before an agent standing
        // next to them can finish onboarding them.
        if (user.requiresEmailVerification()) {
            representation.setRequiredActions(List.of(VERIFY_EMAIL));
        }

        Map<String, List<String>> attributes = new java.util.HashMap<>();
        if (user.agencyId() != null) {
            attributes.put(TokenClaims.AGENCY_ID, List.of(user.agencyId()));
        }
        if (user.partyId() != null) {
            attributes.put(TokenClaims.PARTY_ID, List.of(user.partyId()));
        }
        representation.setAttributes(attributes);

        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(user.password());
        // Temporary for a provisioned account, so the password an admin can see stops
        // working the moment the real person uses it. Not temporary when the person
        // chose it themselves at signup - there is nobody else who has seen it, and
        // demanding they change the password they just picked teaches them nothing.
        password.setTemporary(user.mustChangePassword());
        representation.setCredentials(List.of(password));

        String userId;
        try (Response response = realm().users().create(representation)) {
            if (response.getStatus() == 409) {
                throw new DirectoryException("A user with email " + user.email()
                        + " already exists");
            }
            if (response.getStatus() >= 300) {
                throw new DirectoryException("Keycloak refused to create the user: HTTP "
                        + response.getStatus());
            }
            userId = extractId(response);
        }

        grantRole(userId, user.role());
        if (user.requiresEmailVerification()) {
            sendVerificationEmail(userId, user.email());
        }
        LOG.infof("Created %s %s (agency=%s)", user.role(), user.email(), user.agencyId());

        return findById(userId).orElseThrow(
                () -> new DirectoryException("User " + userId + " vanished after creation"));
    }

    /**
     * Asks Keycloak to mail the verification link now, rather than at first login.
     *
     * <p>Best effort, and deliberately so. The required action is already on the
     * account, so somebody who never receives this can still verify by signing in -
     * Keycloak prompts and sends it again. Failing the whole signup because a mail
     * server was briefly unreachable would destroy an agency over something that fixes
     * itself, and the caller has no way to retry without picking a different email.
     *
     * <p>It matters most for API clients. A browser gets the prompt at login anyway; a
     * password grant just answers "Account is not fully set up" and no mail is ever
     * sent, which is a dead end unless this ran.
     */
    private void sendVerificationEmail(String userId, String email) {
        try {
            realm().users().get(userId).executeActionsEmail(List.of(VERIFY_EMAIL));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not send the verification email to %s. The account is "
                    + "created and will prompt at first sign-in instead.", email);
        }
    }

    /**
     * Grants a role by putting the person in the group that carries it.
     *
     * <h2>Why not assign the role directly</h2>
     *
     * Assigning a realm role through the admin API means reading the role first, to
     * resolve its id - and reading roles requires {@code view-realm}, a composite that
     * carries {@code view-clients} and therefore the ability to read client secrets.
     * That is an enormous privilege for a service whose entire job is creating people,
     * and losing its credentials would then mean losing every client secret too.
     *
     * <p>Joining a group needs only {@code manage-users}. The realm defines one group
     * per role, each carrying that role, so this achieves the same thing while leaving
     * the service account unable to read a single secret.
     */
    @Override
    public void grantRole(String userId, String role) {
        GroupRepresentation group = realm().groups().groups(role, 0, 1).stream()
                .filter(g -> role.equals(g.getName()))
                .findFirst()
                .orElseThrow(() -> new DirectoryException(
                        "The realm has no group '" + role + "'. Provisioning assigns "
                                + "roles through groups - see infra/keycloak."));

        realm().users().get(userId).joinGroup(group.getId());
    }

    @Override
    public void setEnabled(String userId, boolean enabled) {
        UserRepresentation representation = realm().users().get(userId).toRepresentation();
        representation.setEnabled(enabled);
        realm().users().get(userId).update(representation);
    }

    // ---------------------------------------------------------------- mapping

    private PlatformUser toPlatformUser(UserRepresentation representation) {
        Set<String> roles = new HashSet<>();
        try {
            roles = realm().users().get(representation.getId()).roles().realmLevel()
                    .listEffective().stream()
                    .map(RoleRepresentation::getName)
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            // A roster listing should not fail because one user's roles could not be
            // read. The empty set is visibly wrong rather than silently plausible.
            LOG.warnf(e, "Could not read roles for user %s", representation.getId());
        }

        return new PlatformUser(
                representation.getId(),
                representation.getUsername(),
                representation.getEmail(),
                representation.getFirstName(),
                representation.getLastName(),
                roles,
                firstAttribute(representation, TokenClaims.AGENCY_ID),
                firstAttribute(representation, TokenClaims.PARTY_ID),
                representation.isEnabled() != null && representation.isEnabled());
    }

    private static String firstAttribute(UserRepresentation representation, String name) {
        Map<String, List<String>> attributes = representation.getAttributes();
        if (attributes == null) {
            return null;
        }
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** Keycloak returns the new id only in the Location header. */
    private static String extractId(Response response) {
        String location = response.getLocation() == null ? null
                : response.getLocation().getPath();
        if (location == null || !location.contains("/")) {
            throw new DirectoryException("Keycloak did not return a location for the new user");
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
