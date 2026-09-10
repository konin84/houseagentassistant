package com.digitalpartner.houseagent.agency;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Signing in with a phone number instead of an email address.
 *
 * <h2>How it works, and why it is tested here</h2>
 *
 * Keycloak accepts a username or an email at its login form, and nothing else. So the
 * number becomes the <em>username</em>, which gives a person both without a custom
 * Keycloak provider to build, package and keep working across upgrades.
 *
 * <p>That means the interesting behaviour is what this service asks the directory to
 * create - the username it chooses, and whether it lets two people claim one number.
 * Whether Keycloak then honours it at the login form is Keycloak's own behaviour, and is
 * checked against a running realm rather than here.
 */
@QuarkusTest
class PhoneLoginTest {

    private static final String AGENCY = "phone-agency";

    @Inject
    InMemoryUserDirectory directory;

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void alandlordCanBeOnboardedWithTheNumberTheyKnowByHeart() {
        String email = "landlord-" + System.nanoTime() + "@example.ci";

        given()
                .contentType(ContentType.JSON)
                .body(party(email, "07 01 02 03 04"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(201)
                // Reduced to one form, and handed back in it. Showing the agent what
                // they typed would be showing them something the person cannot sign in
                // with - the whole point is that these are now the same string.
                .body("user.phone", equalTo("+2250701020304"));

        // The username is what Keycloak logs people in by, so this is the assertion that
        // actually says "they can sign in with their phone".
        assertEquals("+2250701020304", directory.findByEmail(email).orElseThrow().username());
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAgentAndArenterGetOneToo() {
        // Asked for as "all users", and there is no reason to draw a line. An agent in
        // the field has the same phone habits as the landlords they visit.
        String agent = "agent-" + System.nanoTime() + "@example.ci";
        given().contentType(ContentType.JSON).body(party(agent, "0705060708"))
                .when().post("/api/agency/staff").then().statusCode(201)
                .body("user.phone", equalTo("+2250705060708"));

        String renter = "renter-" + System.nanoTime() + "@example.ci";
        given().contentType(ContentType.JSON).body(party(renter, "+225 07 09 10 11"))
                .when().post("/api/agency/renters").then().statusCode(201)
                .body("user.phone", equalTo("+2250709101 1".replace(" ", "")));
    }

    @Test
    void anAgencySigningItselfUpCanUseItsAdminsNumber() {
        String email = "founder-" + System.nanoTime() + "@example.ci";
        Map<String, Object> body = new HashMap<>();
        body.put("agencyName", "Phone Signup " + System.nanoTime());
        body.put("adminEmail", email);
        body.put("adminPhone", "07 22 33 44 55");
        body.put("password", "chosen-by-me");

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/signup")
                .then()
                .statusCode(201)
                .body("administrator.phone", equalTo("+2250722334455"));
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void thenumberIsOptionalAndTheEmailStillWorksWithoutIt() {
        // Every account that existed before this feature has no number, and must carry
        // on working. Their username stays the email, which is what they sign in with.
        String email = "no-phone-" + System.nanoTime() + "@example.ci";

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "firstName", "No", "lastName", "Phone"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(201)
                .body("user.phone", nullValue());

        assertEquals(email, directory.findByEmail(email).orElseThrow().username());
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void twoPeopleCannotClaimOneNumber() {
        String number = "07 55 55 55 55";
        given().contentType(ContentType.JSON)
                .body(party("first-" + System.nanoTime() + "@example.ci", number))
                .when().post("/api/agency/landlords").then().statusCode(201);

        // A second account on the same number would mean whoever signs in with it
        // reaches whichever of the two the directory happened to return - so it is
        // refused, exactly as a duplicate email is.
        given().contentType(ContentType.JSON)
                .body(party("second-" + System.nanoTime() + "@example.ci", number))
                .when().post("/api/agency/landlords")
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void adifferentSpellingIsTheSameNumberAndIsAlsoRefused() {
        // The case the normalisation exists for. Without it these are two accounts, and
        // the second person discovers at the login form that their number belongs to
        // somebody else.
        given().contentType(ContentType.JSON)
                .body(party("spelling-a-" + System.nanoTime() + "@example.ci", "0766666666"))
                .when().post("/api/agency/landlords").then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body(party("spelling-b-" + System.nanoTime() + "@example.ci",
                        "+225 07-66-66-66-66"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(409)
                .body("message", equalTo("That phone number already belongs to an account"));
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anumberNobodyCouldBeReachedOnIsRefused() {
        given().contentType(ContentType.JSON)
                .body(party("short-" + System.nanoTime() + "@example.ci", "0700"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_PHONE_NUMBER"));
    }

    @Test
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void linkingSomebodyWhoAlreadyExistsDoesNotTripOverTheirOwnNumber() {
        // A landlord working with a second agency is linked, not created. The number
        // check must not fire on the number they already hold - otherwise the second
        // agency simply cannot onboard them, and the message would say their own phone
        // belongs to somebody else.
        String email = "linked-" + System.nanoTime() + "@example.ci";
        directory.seed(email, Roles.LANDLORD, null, "party-linked", "+2250788888888");

        given()
                .contentType(ContentType.JSON)
                .body(party(email, "+225 07 88 88 88 88"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(200)
                .body("linked", equalTo(true));
    }

    private static Map<String, Object> party(String email, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("firstName", "Test");
        body.put("lastName", "Person");
        body.put("phone", phone);
        return body;
    }
}
