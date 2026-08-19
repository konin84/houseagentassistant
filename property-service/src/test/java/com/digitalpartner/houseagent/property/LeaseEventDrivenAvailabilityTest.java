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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * The Phase 2 pivot: house availability is no longer something an agent types, it is
 * derived from whether lease-service says a lease exists.
 *
 * <p>These tests feed lease events straight into the incoming channel, exercising the
 * consumer, the tenant re-establishment and the marketplace projection together,
 * without a broker or the other service running.
 *
 * <p>The one that matters most is {@link #anEventFromAnotherAgencyCannotTouchThisHouse()}.
 * A consumer thread has no JWT, so if it fails to establish the tenant from the event
 * itself, writes land in the wrong agency and nothing fails loudly.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestSecurity(user = "agent-a", roles = Roles.AGENT)
@OidcSecurity(claims = @Claim(key = "agency_id", value = LeaseEventDrivenAvailabilityTest.AGENCY))
class LeaseEventDrivenAvailabilityTest {

    static final String AGENCY = "evt-agency-a";
    private static final String OTHER_AGENCY = "evt-agency-b";
    private static final String CITY = "LeaseEventTestCity";

    @Inject
    @Any
    InMemoryConnector connector;

    private static String houseId;

    @Test
    @Order(1)
    void anAvailablePublishedHouseIsOnTheMarketplace() {
        houseId = given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest("Event-driven villa", CITY))
                .when().post("/api/agency/houses")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .when().post("/api/agency/houses/" + houseId + "/publication")
                .then().statusCode(200);

        assertStatus("AVAILABLE");
        assertListed(true);
    }

    @Test
    @Order(2)
    void aSignedLeaseTakesTheHouseOffTheMarketplace() {
        send(event("LeaseSigned", AGENCY, houseId));

        awaitStatus("RESERVED");
        assertListed(false);
    }

    @Test
    @Order(3)
    void movingInMarksItOccupiedAndItStaysOffTheMarketplace() {
        send(event("LeaseActivated", AGENCY, houseId));

        awaitStatus("OCCUPIED");
        assertListed(false);
    }

    @Test
    @Order(4)
    void redeliveryOfTheSameEventChangesNothing() {
        // At-least-once delivery guarantees this happens in production. Handlers set an
        // absolute state rather than applying a delta, so a repeat is a no-op.
        send(event("LeaseActivated", AGENCY, houseId));
        send(event("LeaseActivated", AGENCY, houseId));

        awaitStatus("OCCUPIED");
        assertListed(false);
    }

    @Test
    @Order(5)
    void endingTheLeasePutsTheHouseBackOnTheMarketplace() {
        send(event("LeaseEnded", AGENCY, houseId));

        awaitStatus("AVAILABLE");
        // It was published before being let, so it returns to the market on its own.
        assertListed(true);
    }

    @Test
    @Order(6)
    void anEventFromAnotherAgencyCannotTouchThisHouse() {
        // Same house id, different agency. The consumer establishes agency B, the tenant
        // filter hides the house, and nothing is written. Were the consumer to stop
        // setting the tenant, this house would silently go RESERVED and the marketplace
        // would lose a listing that belongs there.
        send(event("LeaseSigned", OTHER_AGENCY, houseId));

        await().during(2, TimeUnit.SECONDS)
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertStatus("AVAILABLE"));
        assertListed(true);
    }

    @Test
    @Order(7)
    void anUnparseableEventIsDiscardedRatherThanBlockingTheChannel() {
        send("this is not json");
        send(event("LeaseSigned", AGENCY, houseId));

        // The malformed message must not stall the well-formed one queued behind it.
        awaitStatus("RESERVED");
    }

    // ---------------------------------------------------------------- helpers

    private void send(String payload) {
        connector.source("lease-events").send(payload);
    }

    private static void awaitStatus(String expected) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertStatus(expected));
    }

    private static void assertStatus(String expected) {
        given()
                .when().get("/api/agency/houses/" + houseId)
                .then()
                .statusCode(200)
                .body("status", equalTo(expected));
    }

    private static void assertListed(boolean expected) {
        var response = given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200);

        if (expected) {
            response.body("items.houseId", hasItem(houseId));
        } else {
            response.body("items.houseId", not(hasItem(houseId)));
        }
    }

    private static String event(String type, String agencyId, String house) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "agencyId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "leaseId": "%s",
                    "houseId": "%s",
                    "agencyId": "%s"
                  }
                }
                """.formatted(
                UUID.randomUUID(), type, agencyId, Instant.now(),
                UUID.randomUUID(), house, agencyId);
    }
}
