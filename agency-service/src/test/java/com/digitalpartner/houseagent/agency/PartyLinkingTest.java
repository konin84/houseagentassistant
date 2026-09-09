package com.digitalpartner.houseagent.agency;

import com.digitalpartner.houseagent.common.security.Roles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A landlord who works with two agencies is one person.
 *
 * <p>The platform is built on that: {@code landlord_lease_view} carries no tenant
 * filter precisely so a portfolio spans agencies. If the second agency to onboard
 * somebody created a second {@code party_id}, their leases and earnings would split
 * across two accounts they could never see together - and nothing would fail. It would
 * simply look, to them, as though half their properties had vanished.
 *
 * <p>Which makes linking the interesting behaviour, and duplication the bug worth
 * having a test for.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartyLinkingTest {

    static final String AGENCY_A = "link-agency-a";
    static final String AGENCY_B = "link-agency-b";

    private static final String SHARED_LANDLORD = "shared-landlord@example.ci";

    @Inject
    InMemoryUserDirectory directory;

    private static String partyIdFromA;

    @Test
    @Order(1)
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void twoAgenciesExist() {
        for (String id : new String[]{AGENCY_A, AGENCY_B}) {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("agencyId", id, "name", "Agency " + id))
                    .when().post("/api/platform/agencies")
                    .then().statusCode(201);
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void thefirstAgencyOnboardsALandlord() {
        partyIdFromA = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", SHARED_LANDLORD,
                        "firstName", "Kouassi", "lastName", "Konan"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(201)
                .body("linked", equalTo(false))
                .body("user.roles", hasItem(Roles.LANDLORD))
                // A landlord carries no agency. One on them would make the tenant
                // filter hide their own data the moment they dealt with a second
                // agency - which is the whole problem this design avoids.
                .body("user.agencyId", nullValue())
                .body("user.partyId", notNullValue())
                .body("temporaryPassword", notNullValue())
                .extract().path("user.partyId");

        assertNotNull(partyIdFromA);
    }

    @Test
    @Order(3)
    @TestSecurity(user = "admin-b", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_B))
    void thesecondAgencyLinksRatherThanDuplicating() {
        String partyIdFromB = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", SHARED_LANDLORD,
                        "firstName", "Kouassi", "lastName", "Konan"))
                .when().post("/api/agency/landlords")
                .then()
                // 200, not 201. Nothing was created.
                .statusCode(200)
                .body("linked", equalTo(true))
                // No password: this person already has one, and handing agency B a
                // fresh credential for an account it did not create would be a
                // takeover rather than an introduction.
                .body("temporaryPassword", nullValue())
                .extract().path("user.partyId");

        // The whole point. One person, one id, whichever agency asked.
        assertEquals(partyIdFromA, partyIdFromB,
                "a landlord's portfolio must not split across agencies");
    }

    @Test
    @Order(4)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void somebodyCanBeBothALandlordAndARenter() {
        // Renting a flat in town while letting out a house you inherited is ordinary.
        // One account, two roles - not two accounts.
        String partyId = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", SHARED_LANDLORD,
                        "firstName", "Kouassi", "lastName", "Konan"))
                .when().post("/api/agency/renters")
                .then()
                .statusCode(200)
                .body("linked", equalTo(true))
                .body("user.roles", hasItem(Roles.LANDLORD))
                .body("user.roles", hasItem(Roles.RENTER))
                .extract().path("user.partyId");

        assertEquals(partyIdFromA, partyId);
    }

    @Test
    @Order(5)
    @TestSecurity(user = "admin-b", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_B))
    void anotherAgencysStaffCannotBeAdoptedAsALandlord() {
        // Agency A's employee, found by an address agency B guessed.
        var employee = directory.seed("employee@agency-a.ci", Roles.AGENT, AGENCY_A, null);

        // Without this rule, agency B could add the LANDLORD role to a competitor's
        // staff using nothing but a work email address.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", employee.email(), "firstName", "X", "lastName", "Y"))
                .when().post("/api/agency/landlords")
                .then()
                .statusCode(409)
                .body("code", equalTo("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void anAgentCannotOnboardAnybody() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", "someone@example.ci", "firstName", "A", "lastName", "B"))
                .when().post("/api/agency/landlords")
                .then().statusCode(403);
    }
}
