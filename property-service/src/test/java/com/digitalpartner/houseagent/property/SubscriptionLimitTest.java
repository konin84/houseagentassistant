package com.digitalpartner.houseagent.property;

import com.digitalpartner.houseagent.common.security.Roles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * An agency may hold as many houses as its plan allows, and not one more.
 *
 * <p>The limit is enforced here but decided in agency-service, and arrives as an event.
 * That is deliberate: asking agency-service at the moment somebody adds a house would
 * mean an agency-service outage stopping every agency on the platform from listing
 * anything, in exchange for freshness on a number that changes about once a year.
 *
 * <p>The case worth reading is {@link #adowngradeNeverRemovesAnything()}. An agency that
 * moves to a smaller plan keeps every house it already had - because taking a
 * landlord's advertisement off the market over a billing decision they had no part in
 * would be somebody else's loss.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionLimitTest {

    static final String AGENCY = "sub-agency-a";
    private static final String CITY = "SubscriptionTestCity";

    @Inject
    @Any
    InMemoryConnector connector;

    @Test
    @Order(1)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAgencyNobodyHasHeardOfGetsTheFreeTier() {
        // No plan event has been published for this agency at all. Refusing outright
        // would leave a newly registered agency unable to do anything until a message
        // arrived; allowing everything would hand the unlimited plan to anyone whose
        // event went missing. A brand new agency is on the free plan anyway.
        for (int i = 1; i <= 5; i++) {
            createHouse("Free tier house " + i).then().statusCode(201);
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void thesixthIsRefusedWithSomethingActionable() {
        createHouse("One too many")
                .then()
                // 402, not 403: they are entitled to add houses, just not this many.
                // Not 409 either - nothing is in conflict. The code alone tells a
                // client the answer is a bigger plan rather than a different request.
                .statusCode(402)
                .body("code", equalTo("SUBSCRIPTION_LIMIT_REACHED"))
                // The numbers matter. "You have 5 of 5 on the FREE plan" tells an agent
                // what to do; "forbidden" sends them to support.
                .body("message", containsString("FREE"))
                .body("message", containsString("5"));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void nothingIsLeftBehindByARefusedAttempt() {
        // The check runs before the insert, so the refusal above cannot have written a
        // sixth row that simply failed later.
        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(5));
    }

    @Test
    @Order(4)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void upgradingRaisesTheCeiling() {
        publishPlan(AGENCY, "STARTER", 25);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                createHouse("Sixth, on the bigger plan").then().statusCode(201));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void adowngradeNeverRemovesAnything() {
        publishPlan(AGENCY, "FREE", 5);

        // Six houses, a ceiling of five. The next one is refused...
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                createHouse("Refused after downgrade").then().statusCode(402));

        // ...but all six are still there. A landlord's house does not vanish from the
        // market because their agency changed its billing.
        given()
                .when().get("/api/agency/houses")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(6));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void removingHousesMakesRoomAgain() {
        // Six houses against a ceiling of five, from the downgrade above. Deleting one
        // leaves five - still at the ceiling, so still refused. It takes two to make
        // room, which is the arithmetic actually working rather than a special case for
        // agencies that are over.
        //
        // Deleting needs AGENCY_ADMIN, hence the different role on this one.
        deleteOldestHouse();
        createHouse("Still at the ceiling").then().statusCode(402);

        deleteOldestHouse();
        createHouse("Room made by removing two").then().statusCode(201);
    }

    private static void deleteOldestHouse() {
        String doomed = given()
                .queryParam("size", 100)
                .when().get("/api/agency/houses")
                .then().statusCode(200)
                .extract().path("items[0].id");

        given()
                .when().delete("/api/agency/houses/" + doomed)
                .then().statusCode(204);
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anUnlimitedPlanHasNoCeiling() {
        // maxHouses null rather than a very large number, so "no limit" can never be
        // mistaken for a limit somebody chose.
        publishPlan(AGENCY, "ENTERPRISE", null);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                createHouse("Enterprise house").then().statusCode(201));
        createHouse("And another").then().statusCode(201);
    }

    @Test
    @Order(8)
    @TestSecurity(user = "other", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "sub-agency-b"))
    void oneAgencysHousesDoNotCountAgainstAnother() {
        // The count is tenant-filtered, so it is already this agency's own without
        // saying so. Were it not, one busy agency would exhaust everybody's allowance.
        for (int i = 1; i <= 5; i++) {
            createHouse("Agency B house " + i).then().statusCode(201);
        }
        createHouse("Agency B, one too many").then().statusCode(402);
    }

    // ---------------------------------------------------------------- helpers

    private static io.restassured.response.Response createHouse(String title) {
        return given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest(title, CITY))
                .when().post("/api/agency/houses");
    }

    /** Feeds a plan straight into the incoming channel, as agency-service would. */
    private void publishPlan(String agencyId, String plan, Integer maxHouses) {
        connector.source("agency-events").send("""
                {
                  "eventId": "%s",
                  "eventType": "AgencyPlanChanged",
                  "agencyId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "agencyId": "%s",
                    "plan": "%s",
                    "maxHouses": %s,
                    "occurredAt": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), agencyId, Instant.now(),
                agencyId, plan, maxHouses == null ? "null" : maxHouses, Instant.now()));
    }
}
