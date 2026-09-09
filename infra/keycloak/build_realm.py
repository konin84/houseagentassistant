"""Generates the Keycloak realm import.

Kept as a script because one field - the declarative user profile - is a JSON document
embedded as a *string* inside the realm JSON. Hand-escaping that is how a realm import
ends up silently dropping the two claims this whole platform authorises on.

    python infra/keycloak/build_realm.py
"""

import json
import pathlib

REALM = "houseagent"
CLIENT_ID = "houseagent-backend"
CLIENT_SECRET = "houseagent-dev-secret"

WEB_CLIENT_ID = "houseagent-web"

# agency-service provisions users through Keycloak's admin API, and needs an identity
# of its own to do it. A service account rather than a human's token: provisioning
# happens on behalf of the platform, not on behalf of whoever is logged in.
ADMIN_CLIENT_ID = "houseagent-admin"
ADMIN_CLIENT_SECRET = "houseagent-admin-dev-secret"

# Where the frontend dev server runs. Add to these rather than loosening them:
# Keycloak matches redirect URIs exactly, and a wildcard host would let any site
# start a login and receive the resulting code.
WEB_ORIGINS = ["http://localhost:3000", "http://localhost:5173"]

# Matches the ids already used by the Postman collection and the tests, so a token and
# a seeded database row describe the same person.
LANDLORD_PARTY = "11111111-1111-1111-1111-111111111111"
RENTER_PARTY = "22222222-2222-2222-2222-222222222222"


def attribute_mapper(claim):
    """Copies a user attribute into the token under the same name.

    access.token.claim is the one that matters: the services read a bearer token, so a
    claim that appears only in the ID token would be invisible to them.
    """
    return {
        "name": claim,
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-attribute-mapper",
        "consentRequired": False,
        "config": {
            "user.attribute": claim,
            "claim.name": claim,
            "jsonType.label": "String",
            "access.token.claim": "true",
            "id.token.claim": "true",
            "userinfo.token.claim": "true",
            "introspection.token.claim": "true",
        },
    }


def profile_attribute(name, display, max_length):
    return {
        "name": name,
        "displayName": display,
        "multivalued": False,
        "permissions": {"view": ["admin", "user"], "edit": ["admin"]},
        "validations": {"length": {"max": max_length}},
        "annotations": {},
    }


# Keycloak 24 and later refuse to store attributes the user profile does not declare,
# so agency_id and party_id are declared here explicitly. Without this the import
# succeeds, the users appear correct in the console, and every token comes back without
# the two claims the platform authorises on - which is a genuinely baffling afternoon.
USER_PROFILE = {
    "attributes": [
        {
            "name": "username",
            "displayName": "${username}",
            "validations": {
                "length": {"min": 3, "max": 255},
                "username-prohibited-characters": {},
                "up-username-not-idn-homograph": {},
            },
            "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]},
            "multivalued": False,
        },
        {
            "name": "email",
            "displayName": "${email}",
            "validations": {"email": {}, "length": {"max": 255}},
            "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]},
            "multivalued": False,
        },
        {
            "name": "firstName",
            "displayName": "${firstName}",
            "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}},
            "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]},
            "multivalued": False,
        },
        {
            "name": "lastName",
            "displayName": "${lastName}",
            "validations": {"length": {"max": 255}, "person-name-prohibited-characters": {}},
            "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]},
            "multivalued": False,
        },
        profile_attribute("agency_id", "Agency", 64),
        profile_attribute("party_id", "Party", 64),
    ],
    "groups": [
        {"name": "user-metadata", "displayHeader": "User metadata",
         "displayDescription": "Attributes this platform authorises on"}
    ],
    # Belt and braces: declared attributes above are managed regardless, but this stops
    # an attribute added later from being silently discarded.
    "unmanagedAttributePolicy": "ENABLED",
}


def user(username, roles, agency=None, party=None, first="", last="", email=None):
    attributes = {}
    if agency:
        attributes["agency_id"] = [agency]
    if party:
        attributes["party_id"] = [party]
    return {
        "username": username,
        "enabled": True,
        "emailVerified": True,
        "firstName": first,
        "lastName": last,
        "email": email or (username + "@houseagent.local"),
        "credentials": [{"type": "password", "value": "password", "temporary": False}],
        "realmRoles": roles,
        "attributes": attributes,
    }


realm = {
    "realm": REALM,
    "enabled": True,
    "displayName": "houseagentassistant",

    # Development settings. sslRequired none because this runs on plain HTTP on a
    # laptop; a deployed realm must not do this.
    "sslRequired": "none",
    "registrationAllowed": False,
    "loginWithEmailAllowed": True,
    "duplicateEmailsAllowed": False,
    "resetPasswordAllowed": False,
    "editUsernameAllowed": False,

    # Long enough not to expire mid-session while clicking through Postman, short
    # enough to still be a token rather than a password.
    "accessTokenLifespan": 1800,
    "ssoSessionIdleTimeout": 7200,
    "ssoSessionMaxLifespan": 36000,

    "roles": {
        "realm": [
            {"name": "PLATFORM_ADMIN",
             "description": "Operates the platform itself. Crosses agency boundaries by design."},
            {"name": "AGENCY_ADMIN",
             "description": "Owns one agency's account: billing, staff, settings."},
            {"name": "AGENT",
             "description": "Agency staff who list houses and manage leases."},
            {"name": "LANDLORD",
             "description": "Owns houses. Platform-wide, not agency-scoped."},
            {"name": "RENTER",
             "description": "Rents a house and pays rent. Platform-wide."},
        ]
    },

    # The two claim mappers sit directly on the client rather than in a client scope of
    # their own.
    #
    # A scope reads better, but a realm import creates clients before it creates the
    # built-in scopes, so naming any scope on a client means naming things that do not
    # exist yet. Keycloak logs "Referenced client scope 'roles' doesn't exist. Ignoring"
    # and carries on - and the client silently loses the built-in roles scope, so every
    # token comes back without realm_access.roles and every @RolesAllowed fails. Leaving
    # defaultClientScopes unset lets Keycloak attach its own defaults once they exist.
    # ========================================================================
    #  One group per role, and provisioning assigns groups rather than roles
    # ========================================================================
    #  Assigning a realm role through the admin API requires *reading* the role
    #  first, to resolve its id - and reading roles needs view-realm, which is a
    #  composite that carries view-clients, which can read client secrets. That is
    #  a large privilege for a service whose whole job is creating people.
    #
    #  Adding somebody to a group needs only manage-users. So each role gets a
    #  group that carries it, and agency-service moves users into groups. The
    #  service account stays unable to read a single client secret.
    # ========================================================================
    "groups": [
        {"name": role, "path": "/" + role, "realmRoles": [role]}
        for role in ["AGENCY_ADMIN", "AGENT", "LANDLORD", "RENTER"]
    ],

    "clients": [
        {
            "clientId": CLIENT_ID,
            "name": "houseagentassistant backend",
            "description": ("The four services validate bearer tokens against this "
                            "client, and it also issues them by password grant for "
                            "local testing."),
            "enabled": True,
            "protocol": "openid-connect",
            "publicClient": False,
            "secret": CLIENT_SECRET,
            "bearerOnly": False,
            "serviceAccountsEnabled": False,
            # Off: there is no browser flow here, only bearer tokens.
            "standardFlowEnabled": False,
            "implicitFlowEnabled": False,
            # On: this is how Postman and curl obtain a token for a named user.
            "directAccessGrantsEnabled": True,
            "fullScopeAllowed": True,
            "attributes": {"access.token.lifespan": "1800"},
            "protocolMappers": [attribute_mapper("agency_id"), attribute_mapper("party_id")],
        },
        {
            "clientId": WEB_CLIENT_ID,
            "name": "houseagentassistant web frontend",
            "description": ("Browser client. Authorization code with PKCE, because a "
                            "single-page app cannot keep a secret and must not handle "
                            "the user's password."),
            "enabled": True,
            "protocol": "openid-connect",

            # Public: the app ships to a browser, so anything embedded in it is
            # readable. PKCE is what replaces the secret - the client proves it is
            # the same one that started the flow, without holding anything.
            "publicClient": True,
            "standardFlowEnabled": True,

            # Off deliberately. Password grant would mean the frontend collecting
            # and forwarding the user's actual password, which is what the redirect
            # to Keycloak exists to avoid. It is also gone in OAuth 2.1. The
            # backend client keeps it for Postman and curl, where there is no
            # browser to redirect.
            "directAccessGrantsEnabled": False,
            "implicitFlowEnabled": False,

            "redirectUris": [origin + "/*" for origin in WEB_ORIGINS],
            # "+" means "the redirect URIs above", so the two lists cannot drift.
            "webOrigins": ["+"],

            "fullScopeAllowed": True,
            "attributes": {
                # Makes PKCE mandatory rather than optional. Without this a client
                # can simply omit the challenge and the protection is gone.
                "pkce.code.challenge.method": "S256",
                "access.token.lifespan": "900",
                # Not a top-level field on a client, unlike redirectUris - Keycloak
                # refuses the whole import if it is put there.
                "post.logout.redirect.uris": "+",
            },
            # Repeated from the backend client because the mappers live on clients
            # rather than in a shared scope - see the note above the clients list.
            "protocolMappers": [attribute_mapper("agency_id"), attribute_mapper("party_id")],
        },
        {
            "clientId": ADMIN_CLIENT_ID,
            "name": "houseagentassistant provisioning",
            "description": ("agency-service uses this to create users. Nobody logs in "
                            "with it - it has only a service account."),
            "enabled": True,
            "protocol": "openid-connect",
            "publicClient": False,
            "secret": ADMIN_CLIENT_SECRET,
            "serviceAccountsEnabled": True,
            # No human flow of any kind. This client exists to be one machine.
            "standardFlowEnabled": False,
            "implicitFlowEnabled": False,
            "directAccessGrantsEnabled": False,
            "fullScopeAllowed": True,
        },
    ],

    "users": [
        user("agent-a", ["AGENT"], agency="agency-a",
             first="Adjoua", last="Agent", email="agent-a@agency-a.ci"),
        user("admin-a", ["AGENCY_ADMIN", "AGENT"], agency="agency-a",
             first="Akissi", last="Admin", email="admin-a@agency-a.ci"),
        # A second agency, so isolation can be demonstrated with a real token rather
        # than by editing a header.
        user("agent-b", ["AGENT"], agency="agency-b",
             first="Yao", last="Agent", email="agent-b@agency-b.ci"),
        user("landlord-one", ["LANDLORD"], party=LANDLORD_PARTY,
             first="Kouassi", last="Konan", email="landlord@example.ci"),
        user("renter-one", ["RENTER"], party=RENTER_PARTY,
             first="Ama", last="Kouassi", email="renter@example.ci"),
        user("platform-admin", ["PLATFORM_ADMIN"],
             first="Platform", last="Admin"),
        {
            # The service account behind houseagent-admin. Naming it
            # service-account-<clientId> is how a realm import attaches roles to one.
            #
            # Three roles, not realm-admin: this may create and read users and nothing
            # else. It cannot touch clients, roles or the realm itself, so a leaked
            # secret cannot grant anybody a role they were not already given.
            "username": "service-account-" + ADMIN_CLIENT_ID,
            "enabled": True,
            "serviceAccountClientId": ADMIN_CLIENT_ID,
            "clientRoles": {
                "realm-management": ["manage-users", "view-users", "query-users"]
            },
        },
    ],

    "components": {
        "org.keycloak.userprofile.UserProfileProvider": [
            {
                "name": "Declarative User Profile",
                "providerId": "declarative-user-profile",
                "subType": None,
                "subComponents": {},
                "config": {"kc.user.profile.config": [json.dumps(USER_PROFILE)]},
            }
        ]
    },
}


if __name__ == "__main__":
    out = pathlib.Path(__file__).parent / "houseagent-realm.json"
    out.write_text(json.dumps(realm, indent=2) + "\n", encoding="utf-8")

    # Prove both the realm and the embedded profile parse, since the second one is a
    # string as far as the first is concerned and a typo in it fails silently.
    reloaded = json.loads(out.read_text(encoding="utf-8"))
    embedded = json.loads(
        reloaded["components"]["org.keycloak.userprofile.UserProfileProvider"][0]
        ["config"]["kc.user.profile.config"][0])

    declared = [a["name"] for a in embedded["attributes"]]
    assert "agency_id" in declared and "party_id" in declared, declared

    print("realm:", reloaded["realm"])
    print("roles:", ", ".join(r["name"] for r in reloaded["roles"]["realm"]))
    print("clients:", ", ".join(c["clientId"] for c in reloaded["clients"]))
    print("users:", ", ".join(u["username"] for u in reloaded["users"]))
    print("profile attributes:", ", ".join(declared))
