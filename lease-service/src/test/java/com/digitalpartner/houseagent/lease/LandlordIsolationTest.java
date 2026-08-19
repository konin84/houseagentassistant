package com.digitalpartner.houseagent.lease;

import com.digitalpartner.houseagent.common.security.Roles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * A landlord may read their own tenancies and nobody else's.
 *
 * <p>This is the isolation rule with no safety net. Agency data is narrowed by
 * Hibernate's {@code @TenantId} filter whether or not a query remembers to say so;
 * {@code landlord_lease_view} has no discriminator, because a landlord may work with
 * several agencies and their token names none. The {@code landlord_id} predicate in
 * {@code LandlordPortfolio} is therefore the entire access control, and if it is ever
 * dropped from a query nothing else fails - every landlord simply starts seeing every
 * other landlord's tenants, rents and phone numbers.
 *
 * <p>These tests are what makes that regression loud.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LandlordIsolationTest {

    // Fixed rather than random: @Claim needs a compile-time constant.
    static final String LANDLORD_ONE = "d1111111-1111-1111-1111-111111111111";
    static final String LANDLORD_TWO = "d2222222-2222-2222-2222-222222222222";

    private static final String AGENCY_A = "li-agency-a";
    private static final String AGENCY_B = "li-agency-b";

    private static final String HOUSE_ONE = UUID.randomUUID().toString();
    private static final String HOUSE_TWO = UUID.randomUUID().toString();
    private static final String HOUSE_THREE = UUID.randomUUID().toString();

    private static String leaseWithAgencyA;
    private static String leaseOfOtherLandlord;

    // ------------------------------------------------------------------ setup

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void agencyASignsALeaseForTheFirstLandlord() {
        leaseWithAgencyA = sign(HOUSE_ONE, LANDLORD_ONE, "Ama Kouassi", "2026-01-01", "2026-12-31");
    }

    @Test
    @Order(2)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_B))
    void adifferentAgencySignsAnotherLeaseForTheSameLandlord() {
        // The same landlord, a different agency. A landlord is a platform-wide
        // principal, so their portfolio has to span agencies - which is exactly why
        // this projection cannot be tenant-filtered.
        sign(HOUSE_TWO, LANDLORD_ONE, "Kofi Mensah", "2026-02-01", "2026-11-30");
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void agencyAAlsoSignsALeaseForADifferentLandlord() {
        leaseOfOtherLandlord =
                sign(HOUSE_THREE, LANDLORD_TWO, "Fatou Diallo", "2026-03-01", "2026-10-31");
    }

    // ------------------------------------------------------- what a landlord sees

    @Test
    @Order(4)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_ONE))
    void aLandlordSeesTheirOwnTenanciesAcrossEveryAgency() {
        given()
                .when().get("/api/landlord/leases")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(2))
                .body("items.renterName", containsInAnyOrder("Ama Kouassi", "Kofi Mensah"))
                // The agency is shown so the landlord knows who to call about a house.
                .body("items.managedByAgency", containsInAnyOrder(AGENCY_A, AGENCY_B));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_ONE))
    void anotherLandlordsTenancyIsAbsentFromTheList() {
        given()
                .when().get("/api/landlord/leases")
                .then()
                .statusCode(200)
                .body("items.renterName", not(hasItem("Fatou Diallo")))
                .body("items.leaseId", not(hasItem(leaseOfOtherLandlord)));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_ONE))
    void anotherLandlordsTenancyIsNotFoundRatherThanForbidden() {
        // 404, not 403. A 403 would confirm that this lease id exists, which is itself
        // information a landlord is not entitled to - the same reasoning that makes a
        // foreign house a 404 in property-service.
        given()
                .when().get("/api/landlord/leases/" + leaseOfOtherLandlord)
                .then()
                .statusCode(404)
                .body("code", equalTo("LEASE_NOT_FOUND"));
    }

    @Test
    @Order(7)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_ONE))
    void aLandlordCanReadTheirOwnTenancyInFull() {
        given()
                .when().get("/api/landlord/leases/" + leaseWithAgencyA)
                .then()
                .statusCode(200)
                .body("renterName", equalTo("Ama Kouassi"))
                .body("houseId", equalTo(HOUSE_ONE))
                .body("managedByAgency", equalTo(AGENCY_A))
                .body("rentAmount", equalTo(150000.00f))
                .body("cadence", equalTo("MONTHLY"))
                .body("dueDayOfMonth", equalTo(5))
                // The deposit is held by the agency, not the landlord, so it is not
                // theirs to see.
                .body("depositAmount", nullValue());
    }

    @Test
    @Order(8)
    @TestSecurity(user = "landlord-two", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_TWO))
    void theSecondLandlordSeesOnlyTheirOwn() {
        // The mirror image of the test above. One landlord seeing nothing of another's
        // has to hold in both directions, or the predicate is matching the wrong thing.
        given()
                .when().get("/api/landlord/leases")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(1))
                .body("items.renterName", contains("Fatou Diallo"));

        given()
                .when().get("/api/landlord/leases/" + leaseWithAgencyA)
                .then().statusCode(404);
    }

    // --------------------------------------------------------- current vs history

    @Test
    @Order(9)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void theAgencyEndsOneOfTheTenancies() {
        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.endRequest("Renter relocated"))
                .when().post("/api/agency/leases/" + leaseWithAgencyA + "/termination")
                .then().statusCode(200);
    }

    @Test
    @Order(10)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_ONE))
    void anEndedTenancyLeavesTheCurrentListButRemainsInTheHistory() {
        given()
                .when().get("/api/landlord/leases")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(1))
                .body("items.renterName", contains("Kofi Mensah"));

        given()
                .queryParam("currentOnly", false)
                .when().get("/api/landlord/leases")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(2))
                .body("items.renterName", containsInAnyOrder("Ama Kouassi", "Kofi Mensah"));
    }

    // ------------------------------------------------------------ wrong audience

    @Test
    @Order(11)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void anAgentTokenCannotReadTheLandlordPortfolio() {
        // An agent has no party_id, so there is no "own" portfolio to fall back to.
        // Failing on the role is what stops that ambiguity arising at all.
        given()
                .when().get("/api/landlord/leases")
                .then().statusCode(403);
    }

    @Test
    @Order(12)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD_ONE))
    void aLandlordTokenCannotReachTheAgencyEndpoints() {
        given()
                .when().get("/api/agency/leases")
                .then().statusCode(403);

        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(UUID.randomUUID().toString(), LANDLORD_ONE,
                        "2027-01-01", "2027-12-31"))
                .when().post("/api/agency/leases")
                .then().statusCode(403);
    }

    // ---------------------------------------------------------------- helpers

    private static String sign(String houseId, String landlordId, String renterName,
                               String start, String end) {
        return given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(houseId, landlordId, UUID.randomUUID().toString(),
                        renterName, start, end))
                .when().post("/api/agency/leases")
                .then().statusCode(201)
                .extract().path("id");
    }
}
