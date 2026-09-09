package com.digitalpartner.houseagent.agency;

import com.digitalpartner.houseagent.agency.outbox.OutboxRelay;
import com.digitalpartner.houseagent.common.security.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an agency is entitled to, and who gets to decide it.
 *
 * <p>The limit itself is enforced in property-service, which learns of it from the
 * events asserted here. So the thing worth checking on this side is not a count of
 * houses but that the right people can change a plan, and that a change is actually
 * announced - a plan committed but never published would leave an agency paying for a
 * tier the rest of the platform still refuses to honour.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionPlanTest {

    static final String AGENCY = "plan-agency-a";

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    OutboxRelay relay;

    @Inject
    ObjectMapper objectMapper;

    @Test
    @Order(1)
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void anewAgencyStartsOnTheFreePlan() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("agencyId", AGENCY, "name", "Plan Test Agency"))
                .when().post("/api/platform/agencies")
                .then()
                .statusCode(201)
                // Not a special case to handle later - a brand new agency is simply on
                // the free plan.
                .body("plan", equalTo("FREE"))
                // The ceiling comes from the plan rather than being stored per agency,
                // so raising the free tier is a one-line change and not an UPDATE
                // across every customer.
                .body("maxHouses", equalTo(5));
    }

    @Test
    @Order(2)
    void registrationAnnouncesThePlan() {
        // Announced at creation as well as on change, so property-service never has to
        // guess what a newly registered agency is allowed.
        assertTrue(relay.relayPending() >= 1);

        JsonNode payload = lastPlanEventFor(AGENCY);
        assertNotNull(payload, "registering an agency must announce its plan");
        assertEquals("FREE", payload.get("plan").asText());
        assertEquals(5, payload.get("maxHouses").asInt());
    }

    @Test
    @Order(3)
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void theplatformCanMoveAnAgencyToABiggerPlan() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("plan", "PROFESSIONAL"))
                .when().put("/api/platform/agencies/" + AGENCY + "/plan")
                .then()
                .statusCode(200)
                .body("plan", equalTo("PROFESSIONAL"))
                .body("maxHouses", equalTo(100));
    }

    @Test
    @Order(4)
    void thechangeIsAnnouncedToo() {
        sink().clear();
        assertEquals(1, relay.relayPending());

        JsonNode payload = lastPlanEventFor(AGENCY);
        assertEquals("PROFESSIONAL", payload.get("plan").asText());
        assertEquals(100, payload.get("maxHouses").asInt());
    }

    @Test
    @Order(5)
    @TestSecurity(user = "platform", roles = Roles.PLATFORM_ADMIN)
    void unlimitedIsNullRatherThanAVeryLargeNumber() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("plan", "ENTERPRISE"))
                .when().put("/api/platform/agencies/" + AGENCY + "/plan")
                .then()
                .statusCode(200)
                // Null so that "no ceiling" can never be mistaken for a ceiling
                // somebody chose, and so no arithmetic quietly applies to it.
                .body("maxHouses", nullValue());

        sink().clear();
        relay.relayPending();
        assertTrue(lastPlanEventFor(AGENCY).get("maxHouses").isNull(),
                "unlimited must travel as null, not as a number");
    }

    @Test
    @Order(6)
    @TestSecurity(user = "admin", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAgencyCanSeeItsOwnPlanButNotSetIt() {
        given()
                .when().get("/api/agency/profile")
                .then()
                .statusCode(200)
                .body("plan", equalTo("ENTERPRISE"));

        // Without payment behind it, an agency admin able to call this would simply
        // award themselves the unlimited tier. The decision sits with whoever is doing
        // the billing until there is billing to do it.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("plan", "ENTERPRISE"))
                .when().put("/api/platform/agencies/" + AGENCY + "/plan")
                .then().statusCode(403);
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAgentCannotSeeOrSetPlans() {
        given().when().get("/api/agency/profile").then().statusCode(403);
        given().when().get("/api/platform/agencies").then().statusCode(403);
    }

    // ---------------------------------------------------------------- helpers

    private InMemorySink<String> sink() {
        return connector.sink("agency-events");
    }

    /** The payload of the most recent plan announcement for this agency. */
    private JsonNode lastPlanEventFor(String agencyId) {
        JsonNode found = null;
        for (Message<String> message : sink().received()) {
            try {
                JsonNode envelope = objectMapper.readTree(message.getPayload());
                JsonNode payload = envelope.path("payload");
                if ("AgencyPlanChanged".equals(envelope.path("eventType").asText())
                        && agencyId.equals(payload.path("agencyId").asText())) {
                    found = payload;
                }
            } catch (Exception e) {
                throw new AssertionError("Relayed payload is not JSON: " + message.getPayload(), e);
            }
        }
        return found;
    }
}
