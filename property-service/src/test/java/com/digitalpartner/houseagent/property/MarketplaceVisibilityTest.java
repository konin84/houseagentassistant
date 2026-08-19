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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * The marketplace obeys the opposite rule to the agency API: it is cross-agency on
 * purpose, and it must contain only houses that are genuinely available to let.
 *
 * <p>Both halves are tested here because getting one right and the other wrong is the
 * expensive failure - a marketplace that hides other agencies' houses is a useless
 * product, and one that shows already-let houses wastes every visitor's time.
 *
 * <p>Searches are scoped to a city unique to this class so the assertions do not
 * depend on what other test classes have left in the projection.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MarketplaceVisibilityTest {

    private static final String CITY = "MarketplaceTestCity";

    private static String houseA;
    private static String houseB;
    private static String unpublishedHouse;

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "mkt-agency-a"))
    void agencyAPublishesAHouse() {
        houseA = createHouse("Villa from agency A", 4, "250000.00");

        given()
                .when().post("/api/agency/houses/" + houseA + "/publication")
                .then()
                .statusCode(200)
                .body("published", equalTo(true))
                .body("visibleToPublic", equalTo(true));
    }

    @Test
    @Order(2)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "mkt-agency-a"))
    void agencyAAlsoHasAnUnpublishedHouse() {
        unpublishedHouse = createHouse("Not advertised yet", 2, "80000.00");
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "mkt-agency-b"))
    void agencyBPublishesAHouse() {
        houseB = createHouse("Flat from agency B", 2, "120000.00");

        given()
                .when().post("/api/agency/houses/" + houseB + "/publication")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void marketplaceShowsHousesFromEveryAgencyToAnonymousVisitors() {
        // No @TestSecurity here: this is the anonymous path, and it is the whole
        // reason marketplace_listing exists as a separate, un-tenanted table.
        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items.houseId", hasItems(houseA, houseB));
    }

    @Test
    @Order(5)
    void marketplaceHidesHousesThatWereNeverPublished() {
        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items.houseId", not(hasItem(unpublishedHouse)));
    }

    @Test
    @Order(6)
    void marketplaceNeverExposesLandlordIdentity() {
        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items[0].landlordId", nullValue());
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "mkt-agency-a"))
    void anAgentCannotMarkAHouseOccupiedByHand() {
        // Occupancy is derived from leases as of Phase 2. Allowing an agent to type it
        // directly would let a house leave the market with no lease to justify it, and
        // would put this service at odds with the one that actually knows.
        // LeaseEventDrivenAvailabilityTest covers the path that does work.
        given()
                .contentType(ContentType.JSON)
                .body(TestHouses.statusUpdate("OCCUPIED"))
                .when().patch("/api/agency/houses/" + houseA)
                .then()
                .statusCode(409)
                .body("code", equalTo("HOUSE_STATE_CONFLICT"));

        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items.houseId", hasItem(houseA));
    }

    @Test
    @Order(8)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "mkt-agency-a"))
    void withdrawingAHouseForRenovationRemovesItFromTheMarketplace() {
        // UNAVAILABLE remains the agency's own decision, unlike OCCUPIED.
        given()
                .contentType(ContentType.JSON)
                .body(TestHouses.statusUpdate("UNAVAILABLE"))
                .when().patch("/api/agency/houses/" + houseA)
                .then()
                .statusCode(200)
                .body("visibleToPublic", equalTo(false));

        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items.houseId", not(hasItem(houseA)))
                .body("items.houseId", hasItem(houseB));
    }

    @Test
    @Order(9)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = "mkt-agency-a"))
    void restoringAvailabilityRelistsIt() {
        given()
                .contentType(ContentType.JSON)
                .body(TestHouses.statusUpdate("AVAILABLE"))
                .when().patch("/api/agency/houses/" + houseA)
                .then().statusCode(200);

        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items.houseId", hasItem(houseA));
    }

    @Test
    @Order(10)
    void priceFilterNarrowsResults() {
        given()
                .queryParam("city", CITY)
                .queryParam("maxPrice", "150000.00")
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("items.houseId", hasItem(houseB))
                .body("items.houseId", not(hasItem(houseA)));
    }

    @Test
    @Order(11)
    void pageSizeIsCappedSoCallersCannotRequestEverything() {
        given()
                .queryParam("size", 100_000)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                .body("size", equalTo(100));
    }

    private static String createHouse(String title, int bedrooms, String price) {
        return given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest(title, CITY, bedrooms, price))
                .when().post("/api/agency/houses")
                .then().statusCode(201)
                .extract().path("id");
    }
}
