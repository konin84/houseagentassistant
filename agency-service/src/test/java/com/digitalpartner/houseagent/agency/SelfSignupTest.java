package com.digitalpartner.houseagent.agency;

import com.digitalpartner.houseagent.agency.identity.NewUser;
import com.digitalpartner.houseagent.common.security.Roles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An agency starting itself, with no account and no token.
 *
 * <p>This is the one place on the platform where an anonymous request causes an account
 * to exist, so most of what is worth testing is about what the caller does <em>not</em>
 * get to influence: the plan, the role, and the id their data will be filtered by.
 */
@QuarkusTest
class SelfSignupTest {

    @Inject
    InMemoryUserDirectory directory;

    // ------------------------------------------------------------ the happy path

    @Test
    void anybodyMaySignUpAndLandsOnTheFreePlan() {
        String email = "founder-" + System.nanoTime() + "@example.com";

        given()
                .contentType(ContentType.JSON)
                .body(signup("Kouassi Immobilier", email))
                .when().post("/api/signup")
                .then()
                .statusCode(201)
                // No Authorization header anywhere in this request. That is the point:
                // the person has nothing to authenticate with yet.
                .body("agency.plan", equalTo("FREE"))
                .body("agency.maxHouses", equalTo(5))
                .body("agency.status", equalTo("ACTIVE"))
                .body("administrator.roles", hasItem(Roles.AGENCY_ADMIN))
                // The claim every other service will filter their data by, issued here
                // and never asked for.
                .body("administrator.agencyId", notNullValue())
                // Staff are not a landlord or a renter of anything.
                .body("administrator.partyId", nullValue());
    }

    @Test
    void theIdIsDerivedFromTheNameRatherThanChosen() {
        String email = "derived-" + System.nanoTime() + "@example.com";

        String agencyId = given()
                .contentType(ContentType.JSON)
                .body(signup("Immobilière Étoile du Sud", email))
                .when().post("/api/signup")
                .then().statusCode(201)
                // Accents folded, not dropped. "immobili-re-toile" would be nobody's
                // agency.
                .body("agency.agencyId", containsString("immobiliere-etoile"))
                .extract().path("agency.agencyId");

        // The administrator carries exactly that id, so the agency they can act for is
        // the one that was just created and not one they named.
        given()
                .contentType(ContentType.JSON)
                .body(signup("Second Agency", "second-" + System.nanoTime() + "@example.com"))
                .when().post("/api/signup")
                .then().statusCode(201)
                .body("agency.agencyId", not(equalTo(agencyId)));
    }

    @Test
    void twoAgenciesWithTheSameNameBothGetOne() {
        // Two real businesses can share a name. Refusing the second would mean the
        // platform's tenancy scheme deciding who is allowed to exist.
        String name = "Résidence " + System.nanoTime();

        String first = signUpAndGetId(name, "first-" + System.nanoTime() + "@example.com");
        String second = signUpAndGetId(name, "again-" + System.nanoTime() + "@example.com");

        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.equals(second), "two agencies must never share a discriminator");
        assertTrue(second.startsWith(first) || second.contains("-"),
                "the second should still be recognisable: " + second);
    }

    @Test
    void aNameInAnotherScriptStillProducesAUsableId() {
        // Folds to nothing at all under the Latin-only rule. Rejecting it would refuse
        // an agency for a reason its owner cannot act on, over a value they will never
        // see or type.
        String agencyId = signUpAndGetId("株式会社", "kk-" + System.nanoTime() + "@example.com");

        assertNotNull(agencyId);
        assertTrue(agencyId.matches("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$"),
                "derived ids must satisfy the same pattern a platform admin's must: "
                        + agencyId);
    }

    // ----------------------------------------------------- what cannot be chosen

    @Test
    void aSignupCannotAwardItselfAPlan() {
        String email = "greedy-" + System.nanoTime() + "@example.com";

        Map<String, Object> body = signup("Ambitious Lettings", email);
        // Neither the tier nor its ceiling is a field on the request. Sending them
        // anyway must change nothing - an anonymous caller able to name a plan would
        // name the unlimited one, and nobody is paying for anything yet.
        body.put("plan", "ENTERPRISE");
        body.put("maxHouses", 10_000);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/signup")
                .then()
                .statusCode(201)
                .body("agency.plan", equalTo("FREE"))
                .body("agency.maxHouses", equalTo(5));
    }

    @Test
    void aSignupCannotAwardItselfARole() {
        String email = "escalate-" + System.nanoTime() + "@example.com";

        Map<String, Object> body = signup("Escalation Properties", email);
        body.put("role", Roles.PLATFORM_ADMIN);
        body.put("roles", java.util.List.of(Roles.PLATFORM_ADMIN));

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/signup")
                .then()
                .statusCode(201)
                .body("administrator.roles", hasItem(Roles.AGENCY_ADMIN))
                // The role that crosses every agency boundary is not reachable from an
                // unauthenticated request, whatever that request says.
                .body("administrator.roles", not(hasItem(Roles.PLATFORM_ADMIN)));
    }

    @Test
    void aSignupCannotPlaceItsAdminInsideAnExistingAgency() {
        String email = "infiltrator-" + System.nanoTime() + "@example.com";

        Map<String, Object> body = signup("Infiltrators", email);
        // agency-a is a real agency with real data. A signup that could name it would
        // hand an anonymous caller an AGENCY_ADMIN token for somebody else's customers -
        // which is every isolation rule on the platform defeated in one request.
        body.put("agencyId", "agency-a");

        String assigned = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/signup")
                .then().statusCode(201)
                .extract().path("administrator.agencyId");

        assertEquals("infiltrators", assigned,
                "the id must come from the name, never from the request");
    }

    // ------------------------------------------------------------- the credential

    @Test
    void thePasswordIsTheirsAndIsNotHandedBack() {
        String email = "own-password-" + System.nanoTime() + "@example.com";

        given()
                .contentType(ContentType.JSON)
                .body(signup("Own Password Lettings", email))
                .when().post("/api/signup")
                .then()
                .statusCode(201)
                // Nothing secret comes back. A provisioned account returns a temporary
                // password because somebody else has to pass it on; here the person
                // already knows it, so returning one would put a second credential on
                // the wire for no reason.
                .body("temporaryPassword", nullValue())
                .body("administrator.temporaryPassword", nullValue());

        NewUser asked = directory.creationRequestFor(email);
        assertNotNull(asked);
        assertEquals("chosen-by-me", asked.password());
        assertFalse(asked.mustChangePassword(),
                "a password the person chose themselves must not demand a reset at "
                        + "first login - there is nobody to lock out");
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "signup-staff-agency"))
    void aProvisionedAccountStillGetsAOneTimePassword() {
        // The counterpart, so the distinction above is a decision rather than an
        // accident: when an admin picks the password, it must stop working.
        String email = "provisioned-" + System.nanoTime() + "@example.com";
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "firstName", "Temp", "lastName", "Account"))
                .when().post("/api/agency/staff")
                .then().statusCode(201);

        NewUser asked = directory.creationRequestFor(email);
        assertNotNull(asked);
        assertTrue(asked.mustChangePassword());
    }

    // ------------------------------------------------------- proving the address

    @Test
    void anAgencyAdminHasToProveTheyOwnTheAddress() {
        String email = "unproved-" + System.nanoTime() + "@example.com";

        given()
                .contentType(ContentType.JSON)
                .body(signup("Unproved Lettings", email))
                .when().post("/api/signup")
                .then().statusCode(201);

        // Without this, signing up as contact@a-real-agency.example takes a real
        // business's name on the platform and there is nothing they can do about it.
        // The row still gets created; what they cannot do is sign in.
        assertTrue(directory.creationRequestFor(email).requiresEmailVerification(),
                "an account created from an unauthenticated request must prove its "
                        + "address before it can be used");
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "verification-agency"))
    void nobodyElseDoes() {
        // The friction is spent where an unproved address costs something, and nowhere
        // else. An agent, a landlord and a renter were each typed in by an agency admin
        // who knows them - a wrong address there is a mistake to correct, not an attack.
        //
        // Making them verify would mean an agency cannot finish onboarding a landlord
        // standing in front of them until that landlord goes home and checks their mail.
        String agent = "agent-" + System.nanoTime() + "@example.com";
        given().contentType(ContentType.JSON)
                .body(Map.of("email", agent, "firstName", "A", "lastName", "Gent"))
                .when().post("/api/agency/staff").then().statusCode(201);

        String landlord = "landlord-" + System.nanoTime() + "@example.com";
        given().contentType(ContentType.JSON)
                .body(Map.of("email", landlord, "firstName", "L", "lastName", "Ord"))
                .when().post("/api/agency/landlords").then().statusCode(201);

        String renter = "renter-" + System.nanoTime() + "@example.com";
        given().contentType(ContentType.JSON)
                .body(Map.of("email", renter, "firstName", "R", "lastName", "Enter"))
                .when().post("/api/agency/renters").then().statusCode(201);

        for (String email : java.util.List.of(agent, landlord, renter)) {
            assertFalse(directory.creationRequestFor(email).requiresEmailVerification(),
                    email + " should not have to verify anything");
        }
    }

    @Test
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void soDoesAnAdminTheePlatformCreated() {
        // The rule is about the role, not about how the account came to exist. An agency
        // admin created on somebody's behalf is no less powerful than one who signed
        // themselves up, and one rule in one place cannot drift between call sites.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("agencyId", "verified-by-platform", "name", "Platform Made"))
                .when().post("/api/platform/agencies")
                .then().statusCode(201);

        String email = "platform-made-" + System.nanoTime() + "@example.com";
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "firstName", "P", "lastName", "Made"))
                .when().post("/api/platform/agencies/verified-by-platform/administrators")
                .then().statusCode(201);

        assertTrue(directory.creationRequestFor(email).requiresEmailVerification());
        // And still gets a one-time password, so they do both.
        assertTrue(directory.creationRequestFor(email).mustChangePassword());
    }

    // -------------------------------------------------------------- what refuses

    @Test
    void anAddressThatAlreadyHasAnAccountIsRefused() {
        String email = "taken-" + System.nanoTime() + "@example.com";
        directory.seed(email, Roles.RENTER, null, "party-1");

        given()
                .contentType(ContentType.JSON)
                .body(signup("Duplicate Lettings", email))
                .when().post("/api/signup")
                .then()
                .statusCode(409)
                .body("code", equalTo("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void aFailedAccountLeavesNoAgencyBehind() {
        String email = "doomed-" + System.nanoTime() + "@example.com";
        directory.failNextCreate();

        given()
                .contentType(ContentType.JSON)
                .body(signup("Doomed Holdings", email))
                .when().post("/api/signup")
                .then().statusCode(502);

        // An agency whose administrator was never created is one nobody can ever act
        // for - and it would have taken the name. Signing up again must work, and get
        // the id the first attempt did not keep.
        given()
                .contentType(ContentType.JSON)
                .body(signup("Doomed Holdings", "retry-" + System.nanoTime() + "@example.com"))
                .when().post("/api/signup")
                .then()
                .statusCode(201)
                .body("agency.agencyId", equalTo("doomed-holdings"));
    }

    @Test
    void aShortPasswordIsRefusedBeforeAnythingIsCreated() {
        Map<String, Object> body = signup("Weak Security Ltd",
                "weak-" + System.nanoTime() + "@example.com");
        body.put("password", "short");

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/signup")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "upgrade-seekers"))
    void anAgencyAdminCannotRaiseTheirOwnPlanAfterSigningUp() {
        String email = "upgrader-" + System.nanoTime() + "@example.com";
        String agencyId = signUpAndGetId("Upgrade Seekers", email);
        assertEquals("upgrade-seekers", agencyId);

        // Signing yourself up gets you an agency on the free plan and no way off it on
        // your own. Until there is billing, an admin able to call this would award
        // themselves the unlimited tier the moment they hit five houses - so the
        // "until he upgrades" part of self-signup is still somebody else's decision.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("plan", "ENTERPRISE"))
                .when().put("/api/platform/agencies/" + agencyId + "/plan")
                .then().statusCode(403);

        // And the agency really is still on the free five.
        given()
                .when().get("/api/agency/profile")
                .then()
                .statusCode(200)
                .body("plan", equalTo("FREE"))
                .body("maxHouses", equalTo(5));
    }

    @Test
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void theSelfSignedAgencyIsAnOrdinaryAgencyAfterwards() {
        // Nothing about it is second class - the platform can see it, and move it to a
        // paid plan, exactly as it would one it created itself.
        String agencyId = signUpAndGetId("Ordinary Lettings",
                "ordinary-" + System.nanoTime() + "@example.com");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("plan", "STARTER"))
                .when().put("/api/platform/agencies/" + agencyId + "/plan")
                .then()
                .statusCode(200)
                .body("plan", equalTo("STARTER"))
                .body("maxHouses", equalTo(25));
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> signup(String agencyName, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("agencyName", agencyName);
        body.put("city", "Abidjan");
        body.put("countryCode", "CI");
        body.put("contactPhone", "+225 07 00 00 00");
        body.put("adminEmail", email);
        body.put("firstName", "Ama");
        body.put("lastName", "Konin");
        body.put("password", "chosen-by-me");
        return body;
    }

    private static String signUpAndGetId(String name, String email) {
        return given()
                .contentType(ContentType.JSON)
                .body(signup(name, email))
                .when().post("/api/signup")
                .then().statusCode(201)
                .extract().path("agency.agencyId");
    }
}
