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
import static org.hamcrest.Matchers.equalTo;

/**
 * The database, not the application, is what prevents a house being let twice.
 *
 * <p>Checking for a conflicting lease and then inserting loses the race when two agents
 * sign at the same instant: both read "free" before either writes. The
 * {@code no_overlapping_active_lease} exclusion constraint removes the race entirely -
 * exactly one transaction can commit, whatever the timing or the bug in the caller.
 *
 * <p>These tests drive that constraint through the API and assert the behaviour a
 * client sees.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DoubleLettingTest {

    private static final String HOUSE = "aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa";
    private static final String LANDLORD = "bbbbbbbb-1111-1111-1111-bbbbbbbbbbbb";

    private static String firstLeaseId;

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-a"))
    void theFirstLeaseIsAccepted() {
        firstLeaseId = given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-01-01", "2026-12-31"))
                .when().post("/api/agency/leases")
                .then()
                .statusCode(201)
                .body("status", equalTo("PENDING_MOVE_IN"))
                .body("cadence", equalTo("MONTHLY"))
                // Rent is due on the 5th, and the lease starts on the 1st, so the first
                // due date is in the starting month rather than the next one.
                .body("firstDueDate", equalTo("2026-01-05"))
                .extract().path("id");
    }

    @Test
    @Order(2)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-a"))
    void anOverlappingLeaseOnTheSameHouseIsRejected() {
        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-06-01", "2027-05-31"))
                .when().post("/api/agency/leases")
                .then()
                .statusCode(409)
                .body("code", equalTo("HOUSE_ALREADY_LET"));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-b"))
    void adifferentAgencyCannotLetTheSameHouseEither() {
        // The constraint is deliberately not scoped by agency. Two agencies letting the
        // same physical house simultaneously is the worst version of this bug, not an
        // exception to it - and it is precisely what the tenant filter would hide,
        // since agency B cannot see agency A's lease at all.
        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-03-01", "2026-09-30"))
                .when().post("/api/agency/leases")
                .then()
                .statusCode(409)
                .body("code", equalTo("HOUSE_ALREADY_LET"));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-a"))
    void aBackToBackLeaseIsAllowed() {
        // The range is half-open, '[)', so a lease ending on 31 December and another
        // starting that same day do not overlap. Consecutive tenancies are normal and
        // must not be rejected.
        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-12-31", "2027-06-30"))
                .when().post("/api/agency/leases")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(5)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-a"))
    void endingALeaseFreesTheHouseForThatPeriod() {
        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.endRequest("Renter left early"))
                .when().post("/api/agency/leases/" + firstLeaseId + "/termination")
                .then()
                .statusCode(200)
                // Ended before move-in, so it is a cancellation rather than a completed
                // tenancy. Either way the house is free.
                .body("status", equalTo("CANCELLED"));

        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-06-01", "2026-11-30"))
                .when().post("/api/agency/leases")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(6)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-a"))
    void anOpenEndedLeaseBlocksEverythingAfterItsStart() {
        String otherHouse = UUID.randomUUID().toString();

        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(otherHouse, LANDLORD, "2026-01-01", null))
                .when().post("/api/agency/leases")
                .then().statusCode(201);

        // A null end date is an unbounded range, so any later period overlaps it.
        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(otherHouse, LANDLORD, "2030-01-01", "2030-12-31"))
                .when().post("/api/agency/leases")
                .then()
                .statusCode(409)
                .body("code", equalTo("HOUSE_ALREADY_LET"));
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "dl-agency-a"))
    void aDueDayBeyond28IsRejected() {
        var body = TestLeases.signRequest(UUID.randomUUID().toString(), LANDLORD,
                "2026-01-01", "2026-12-31");
        body.put("dueDayOfMonth", 31);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/agency/leases")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }
}
