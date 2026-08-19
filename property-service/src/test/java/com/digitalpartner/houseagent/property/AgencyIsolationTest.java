package com.digitalpartner.houseagent.property;

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * The test that has to pass before anything else in this platform matters.
 *
 * <p>If an agent from one agency can see, edit or even confirm the existence of
 * another agency's houses, every other feature is built on sand. These assertions
 * exercise the Hibernate {@code @TenantId} filter end to end through real HTTP calls,
 * not by unit-testing the resolver in isolation - the interesting failures live in
 * the wiring, not the resolver.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgencyIsolationTest {

    private static final String CITY = "IsolationTestCity";
    private static String agencyAHouseId;

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = {
            @Claim(key = "agency_id", value = "agency-a"),
            @Claim(key = "party_id", value = "aaaaaaaa-0000-0000-0000-000000000001")
    })
    void agencyACreatesAHouse() {
        agencyAHouseId = given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest("Agency A villa", CITY))
                .when().post("/api/agency/houses")
                .then()
                .statusCode(201)
                .body("title", equalTo("Agency A villa"))
                .body("status", equalTo("AVAILABLE"))
                // A new house is never advertised until an agent publishes it.
                .body("published", equalTo(false))
                .body("visibleToPublic", equalTo(false))
                .extract().path("id");
    }

    @Test
    @Order(2)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "agency-a"))
    void agencyASeesItsOwnHouse() {
        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(200)
                .body("items.id", hasItem(agencyAHouseId));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "agency-b"))
    void agencyBCannotListAgencyAsHouse() {
        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(200)
                .body("items.id", not(hasItem(agencyAHouseId)));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "agency-b"))
    void agencyBGetsA404NotA403ForAgencyAsHouse() {
        // 404 rather than 403 on purpose: a 403 would confirm the house exists,
        // which leaks the shape of a competitor's portfolio one id at a time.
        given()
                .when().get("/api/agency/houses/" + agencyAHouseId)
                .then()
                .statusCode(404)
                .body("code", equalTo("HOUSE_NOT_FOUND"));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "agency-b"))
    void agencyBCannotEditAgencyAsHouse() {
        given()
                .contentType(ContentType.JSON)
                .body(TestHouses.statusUpdate("UNAVAILABLE"))
                .when().patch("/api/agency/houses/" + agencyAHouseId)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(6)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "agency-b"))
    void agencyBOnlyEverSeesItsOwnHouses() {
        given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest("Agency B flat", CITY))
                .when().post("/api/agency/houses")
                .then().statusCode(201);

        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(200)
                .body("items.title", everyItem(equalTo("Agency B flat")));
    }

    @Test
    @Order(7)
    void anonymousCallersAreRejectedFromTheAgencyApi() {
        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(8)
    @TestSecurity(user = "some-renter", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = "cccccccc-0000-0000-0000-000000000003"))
    void rentersAreRejectedFromTheAgencyApi() {
        // A renter token has no agency_id at all. It must not fall back to a default
        // tenant and quietly return somebody's houses.
        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(403);
    }
}
