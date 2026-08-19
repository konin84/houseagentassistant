package com.digitalpartner.houseagent.property;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.property.images.CloudinarySignatures;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Photographs live at Cloudinary and never pass through this service.
 *
 * <p>That is only safe because of one check, and most of what follows is about it: a
 * signature authorises an upload to one path, and registration refuses any public id
 * outside it. Without that, an agency holding an arbitrary string could attach a
 * competitor's photograph to its own listing.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HouseImageTest {

    static final String AGENCY = "img-agency-a";
    private static final String OTHER_AGENCY = "img-agency-b";
    private static final String CITY = "ImageTestCity";

    @Inject
    CloudinarySignatures signatures;

    private static String houseId;
    private static String firstImageId;
    private static String secondImageId;
    private static String firstPublicId;

    // ------------------------------------------------------- the signature itself

    @Test
    @Order(1)
    void theSignatureMatchesCloudinarysScheme() {
        // A known-answer test against a fixed secret. Cloudinary's rule is: sort the
        // parameters by name, join them as k=v with &, append the secret, hash. If this
        // drifts, every upload is rejected by Cloudinary with no clue why - so it is
        // pinned to a hash computed independently of this code.
        Map<String, String> params = new LinkedHashMap<>();
        // Deliberately out of order, to prove the sort is real and not incidental.
        params.put("timestamp", "1700000000");
        params.put("public_id", "houses/agency-x/house-y/img");

        assertEquals("c8166451f989dc79a5710b84555eb715fa270762", signatures.sign(params));
    }

    @Test
    @Order(2)
    void emptyParametersAreLeftOutOfTheSignature() {
        // Cloudinary omits empty values when it verifies. Including them here would
        // produce a signature the account cannot reproduce.
        Map<String, String> withEmpty = new LinkedHashMap<>();
        withEmpty.put("timestamp", "1700000000");
        withEmpty.put("public_id", "houses/agency-x/house-y/img");
        withEmpty.put("folder", "");

        assertEquals("c8166451f989dc79a5710b84555eb715fa270762", signatures.sign(withEmpty));
    }

    // ------------------------------------------------------------ upload tickets

    @Test
    @Order(3)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void aticketIsScopedToTheAgencyAndHouse() {
        houseId = given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest("Photographed villa", CITY))
                .when().post("/api/agency/houses")
                .then().statusCode(201)
                .extract().path("id");

        var ticket = given()
                .when().post("/api/agency/houses/" + houseId + "/images/upload-ticket")
                .then()
                .statusCode(200)
                .body("uploadUrl", equalTo("https://api.cloudinary.com/v1_1/test-cloud/image/upload"))
                // The path is what the signature is bound to, so an agency cannot obtain
                // one that would overwrite another agency's asset.
                .body("publicId", startsWith("houses/" + AGENCY + "/" + houseId + "/"))
                .body("params.signature", notNullValue())
                .body("params.api_key", equalTo("123456789012345"))
                .extract().jsonPath();

        firstPublicId = ticket.getString("publicId");

        // The secret signs the request and must never travel with it.
        assertTrue(ticket.getMap("params").values().stream()
                        .noneMatch(v -> "test-api-secret".equals(String.valueOf(v))),
                "the API secret must never appear in a response");
    }

    @Test
    @Order(4)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = OTHER_AGENCY))
    void anotherAgencyCannotGetATicketForThisHouse() {
        // 404, not 403 - the same answer as for a house that does not exist, so this
        // endpoint cannot be used to discover another agency's house ids.
        given()
                .when().post("/api/agency/houses/" + houseId + "/images/upload-ticket")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------- registration

    @Test
    @Order(5)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void apublicIdOutsideTheSignedPathIsRefused() {
        // The check that makes direct-to-Cloudinary upload safe. By this point the
        // caller is holding an arbitrary string, and it has to be treated as hostile.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("publicId", "houses/" + OTHER_AGENCY + "/somewhere/stolen",
                        "cover", false))
                .when().post("/api/agency/houses/" + houseId + "/images")
                .then()
                .statusCode(403)
                .body("code", equalTo("FOREIGN_ASSET"));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void thefirstImageRegisteredBecomesTheCover() {
        firstImageId = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "publicId", firstPublicId,
                        "width", 1600,
                        "height", 1200,
                        "format", "jpg",
                        "bytes", 482913,
                        "cover", false))
                .when().post("/api/agency/houses/" + houseId + "/images")
                .then()
                .statusCode(201)
                // Requested cover:false, but a house with photographs and no cover shows
                // a blank card on the marketplace, which no agent intends.
                .body("cover", equalTo(true))
                .body("position", equalTo(0))
                .body("width", equalTo(1600))
                .body("url", containsString("/image/upload/"))
                .body("thumbnailUrl", containsString("c_fill"))
                .extract().path("id");
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void urlsAreDerivedFromThePublicIdRatherThanStored() {
        given()
                .when().get("/api/agency/houses/" + houseId + "/images")
                .then()
                .statusCode(200)
                .body("[0].publicId", equalTo(firstPublicId))
                .body("[0].url", equalTo(
                        "https://res.cloudinary.com/test-cloud/image/upload/"
                                + "c_limit,w_1600,q_auto,f_auto/" + firstPublicId))
                .body("[0].thumbnailUrl", equalTo(
                        "https://res.cloudinary.com/test-cloud/image/upload/"
                                + "c_fill,g_auto,w_400,h_300,q_auto,f_auto/" + firstPublicId));
    }

    // -------------------------------------------------------------- the gallery

    @Test
    @Order(8)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void asecondImageIsAppendedAndIsNotTheCover() {
        String publicId = given()
                .when().post("/api/agency/houses/" + houseId + "/images/upload-ticket")
                .then().statusCode(200)
                .extract().path("publicId");

        secondImageId = given()
                .contentType(ContentType.JSON)
                .body(Map.of("publicId", publicId, "cover", false))
                .when().post("/api/agency/houses/" + houseId + "/images")
                .then()
                .statusCode(201)
                .body("position", equalTo(1))
                .body("cover", equalTo(false))
                .extract().path("id");
    }

    @Test
    @Order(9)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void promotingAnImageDemotesTheOldCover() {
        // At most one cover per house is a partial unique index, not application logic.
        // Setting the new one without clearing the old is a constraint violation, which
        // is exactly the mistake worth having a database catch.
        given()
                .when().put("/api/agency/houses/" + houseId + "/images/" + secondImageId + "/cover")
                .then()
                .statusCode(200)
                .body("cover", equalTo(true));

        given()
                .when().get("/api/agency/houses/" + houseId + "/images")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + firstImageId + "' }.cover", equalTo(false))
                .body("find { it.id == '" + secondImageId + "' }.cover", equalTo(true));
    }

    // ------------------------------------------------------------ marketplace

    @Test
    @Order(10)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void thepublishedListingCarriesTheCoverThumbnail() {
        given()
                .when().post("/api/agency/houses/" + houseId + "/publication")
                .then().statusCode(200);

        given()
                .queryParam("city", CITY)
                .when().get("/api/marketplace/listings")
                .then()
                .statusCode(200)
                // Anonymous callers get a card-sized, auto-format image - which on a
                // phone over mobile data is the difference between a page that loads
                // and one that does not.
                .body("items[0].coverImageUrl", containsString("c_fill,g_auto,w_400,h_300"));
    }

    // ---------------------------------------------------------------- deletion

    @Test
    @Order(11)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void deletingTheCoverPromotesTheNextImage() {
        given()
                .when().delete("/api/agency/houses/" + houseId + "/images/" + secondImageId)
                .then().statusCode(204);

        // A gallery that silently loses its marketplace thumbnail because someone
        // deleted one picture is a bug reported as "our listing disappeared".
        given()
                .when().get("/api/agency/houses/" + houseId + "/images")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(firstImageId))
                .body("[0].cover", equalTo(true));
    }

    @Test
    @Order(12)
    @TestSecurity(user = "agent-b", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = OTHER_AGENCY))
    void anotherAgencyCannotDeleteThisHousesImages() {
        given()
                .when().delete("/api/agency/houses/" + houseId + "/images/" + firstImageId)
                .then().statusCode(404);
    }

    @Test
    @Order(13)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void ahouseWithNoPhotographsHasNoCoverUrl() {
        String bare = given()
                .contentType(ContentType.JSON)
                .body(TestHouses.createRequest("Unphotographed villa", CITY))
                .when().post("/api/agency/houses")
                .then().statusCode(201)
                .extract().path("id");

        // Null rather than a placeholder: a client can tell "no photo" from "a photo
        // that will 404", and a string that looks like a URL but resolves to nothing is
        // worse than neither.
        given()
                .when().get("/api/agency/houses/" + bare)
                .then()
                .statusCode(200)
                .body("images", equalTo(java.util.List.of()));

        given()
                .when().post("/api/agency/houses/" + bare + "/publication")
                .then().statusCode(200);

        given()
                .when().get("/api/marketplace/listings/" + bare)
                .then()
                .statusCode(200)
                .body("coverImageUrl", nullValue());
    }
}
