package com.digitalpartner.houseagent.agency;

import com.digitalpartner.houseagent.agency.identity.NewUser;
import com.digitalpartner.houseagent.agency.identity.PlatformUser;
import com.digitalpartner.houseagent.agency.identity.UserDirectory;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for Keycloak.
 *
 * <p>Everything worth testing in this service is a decision about who may create whom -
 * that an agency admin cannot place staff in another agency, cannot mint a second
 * admin, and cannot adopt a rival's employee as their landlord. None of that is
 * Keycloak's behaviour, and requiring a container to check it would make the one part
 * of the platform that grants access the only part nobody runs the tests for.
 *
 * <p>{@code @Mock} replaces the {@code @DefaultBean} Keycloak implementation for tests
 * only.
 */
@Mock
@ApplicationScoped
public class InMemoryUserDirectory implements UserDirectory {

    private final Map<String, PlatformUser> byId = new ConcurrentHashMap<>();

    /** Lets a test arrange somebody who already exists, as a second agency would find. */
    public PlatformUser seed(String email, String role, String agencyId, String partyId) {
        String id = UUID.randomUUID().toString();
        PlatformUser user = new PlatformUser(
                id, email, email, "Seeded", "User",
                new HashSet<>(Set.of(role)), agencyId, partyId, true);
        byId.put(id, user);
        return user;
    }

    public void clear() {
        byId.clear();
    }

    @Override
    public Optional<PlatformUser> findByEmail(String email) {
        return byId.values().stream()
                .filter(u -> u.email().equalsIgnoreCase(email.trim()))
                .findFirst();
    }

    @Override
    public Optional<PlatformUser> findById(String userId) {
        return Optional.ofNullable(byId.get(userId));
    }

    @Override
    public List<PlatformUser> findByAgency(String agencyId) {
        return byId.values().stream()
                .filter(u -> agencyId.equals(u.agencyId()))
                .toList();
    }

    @Override
    public PlatformUser create(NewUser user) {
        String id = UUID.randomUUID().toString();
        PlatformUser created = new PlatformUser(
                id, user.email(), user.email(), user.firstName(), user.lastName(),
                new HashSet<>(Set.of(user.role())), user.agencyId(), user.partyId(), true);
        byId.put(id, created);
        return created;
    }

    @Override
    public void grantRole(String userId, String role) {
        byId.computeIfPresent(userId, (id, user) -> {
            Set<String> roles = new HashSet<>(user.roles());
            roles.add(role);
            return new PlatformUser(user.userId(), user.username(), user.email(),
                    user.firstName(), user.lastName(), roles, user.agencyId(),
                    user.partyId(), user.enabled());
        });
    }

    @Override
    public void setEnabled(String userId, boolean enabled) {
        byId.computeIfPresent(userId, (id, user) -> new PlatformUser(
                user.userId(), user.username(), user.email(), user.firstName(),
                user.lastName(), user.roles(), user.agencyId(), user.partyId(), enabled));
    }
}
