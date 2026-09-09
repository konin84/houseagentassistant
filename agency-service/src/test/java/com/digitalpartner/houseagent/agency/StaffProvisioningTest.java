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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * An agency admin may staff their own agency, and only their own.
 *
 * <p>The stakes here are higher than they look. The {@code agency_id} stamped on a new
 * account is the value every other service on the platform filters its data by, so an
 * admin who could choose it would be able to create an employee inside a competitor -
 * and that employee's token would then unlock the competitor's houses, leases and
 * money. One request would defeat the entire tenancy model.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaffProvisioningTest {

    static final String AGENCY = "prov-agency-a";
    private static final String OTHER_AGENCY = "prov-agency-b";

    @Inject
    InMemoryUserDirectory directory;

    private static String createdUserId;

    @Test
    @Order(1)
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void theplatformCreatesTheAgencies() {
        for (String id : new String[]{AGENCY, OTHER_AGENCY}) {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("agencyId", id, "name", "Agency " + id,
                            "city", "Abidjan", "countryCode", "CI"))
                    .when().post("/api/platform/agencies")
                    .then().statusCode(201);
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAdminAddsAnAgentToTheirOwnAgency() {
        createdUserId = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", "new-agent@agency-a.ci",
                        "firstName", "Adjoua", "lastName", "Agent"))
                .when().post("/api/agency/staff")
                .then()
                .statusCode(201)
                // The agency came from the token. There is no field for it in the
                // request, which is the point.
                .body("user.agencyId", equalTo(AGENCY))
                .body("user.roles", hasItem(Roles.AGENT))
                // Staff are not a landlord or a renter of anything.
                .body("user.partyId", nullValue())
                .body("temporaryPassword", notNullValue())
                .body("linked", equalTo(false))
                .extract().path("user.userId");
    }

    @Test
    @Order(3)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void therequestBodyHasNoWayToNameAnotherAgency() {
        // Sending one anyway changes nothing: the field is not read, and the new agent
        // still lands in the caller's own agency. This is the test that would fail if
        // somebody ever "helpfully" bound an agencyId parameter.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", "sneaky@agency-a.ci",
                        "firstName", "S", "lastName", "N",
                        "agencyId", OTHER_AGENCY))
                .when().post("/api/agency/staff")
                .then()
                .statusCode(201)
                .body("user.agencyId", equalTo(AGENCY));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void theRosterShowsOnlyThisAgency() {
        given()
                .when().get("/api/agency/staff")
                .then()
                .statusCode(200)
                .body("email", hasItem("new-agent@agency-a.ci"));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "admin-b", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = OTHER_AGENCY))
    void anotherAgencyseesNoneOfIt() {
        given()
                .when().get("/api/agency/staff")
                .then()
                .statusCode(200)
                .body("email", not(hasItem("new-agent@agency-a.ci")));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "admin-b", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = OTHER_AGENCY))
    void anotherAgencyCannotSuspendThisAgencysStaff() {
        // 404 rather than 403: a different answer would confirm that the id belongs to
        // somebody, which is enough to make it worth guessing.
        given()
                .when().delete("/api/agency/staff/" + createdUserId)
                .then()
                .statusCode(404)
                .body("code", equalTo("STAFF_NOT_FOUND"));
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAgentCannotAddStaffAtAll() {
        // An agent sells houses. Deciding who else works here is not their job.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", "x@agency-a.ci", "firstName", "X", "lastName", "Y"))
                .when().post("/api/agency/staff")
                .then().statusCode(403);
    }

    @Test
    @Order(8)
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void aplatformAdminCannotUseTheAgencyEndpointsEither() {
        // The most privileged role on the platform, and it is refused - because it
        // carries no agency_id, and these endpoints need an agency to act within.
        // Privilege is not the same thing as belonging somewhere.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", "y@agency-a.ci", "firstName", "X", "lastName", "Y"))
                .when().post("/api/agency/staff")
                .then().statusCode(403);
    }

    @Test
    @Order(9)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anEmailCanOnlyBelongToOneAccount() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", "new-agent@agency-a.ci",
                        "firstName", "Duplicate", "lastName", "Agent"))
                .when().post("/api/agency/staff")
                .then()
                .statusCode(409)
                .body("code", equalTo("EMAIL_ALREADY_REGISTERED"))
                // The message must not say whose account it is, or which agency they
                // are in. That an address is taken is unavoidable; who has it is not.
                .body("message", not(org.hamcrest.Matchers.containsString(AGENCY)));
    }

    @Test
    @Order(10)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void suspendingWithdrawsAccessWithoutDeletingThePerson() {
        given()
                .when().delete("/api/agency/staff/" + createdUserId)
                .then().statusCode(204);

        // Still on the roster, still explicable on every lease they signed - just
        // disabled. Deleting them would leave those records pointing at nobody.
        given()
                .when().get("/api/agency/staff")
                .then()
                .statusCode(200)
                .body("find { it.userId == '" + createdUserId + "' }.enabled", equalTo(false));
    }
}
