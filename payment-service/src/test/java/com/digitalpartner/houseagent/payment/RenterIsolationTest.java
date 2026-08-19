package com.digitalpartner.houseagent.payment;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.service.InvoiceGenerator;
import com.digitalpartner.houseagent.payment.service.RenterLedger;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A renter may read - and pay - their own rent and nobody else's.
 *
 * <p>This is the third of the platform's isolation rules, and like the landlord's it
 * has no safety net. {@code renter_invoice_view} carries no {@code @TenantId} because a
 * renter may rent from several agencies and their token names none, so the
 * {@code renter_id} predicate in {@code RenterLedger} is the entire access control. If
 * it is ever dropped, every renter starts seeing every other renter's arrears, and
 * nothing else fails.
 *
 * <p>The payment path matters even more than the read path: an invoice a renter can
 * reach is an invoice a renter can pay, which would let anyone spend their own money
 * settling a stranger's rent.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RenterIsolationTest {

    static final String RENTER_ONE = "f1111111-1111-1111-1111-111111111111";
    static final String RENTER_TWO = "f2222222-2222-2222-2222-222222222222";
    static final String LANDLORD_ONE = "f3333333-3333-3333-3333-333333333333";

    private static final String AGENCY_A = "ri-agency-a";
    private static final String AGENCY_B = "ri-agency-b";

    private static final String LEASE_A = "f4444444-4444-4444-4444-444444444444";
    private static final String LEASE_B = "f5555555-5555-5555-5555-555555555555";
    private static final String LEASE_OTHER = "f6666666-6666-6666-6666-666666666666";

    // One period each, so the counts below say something about isolation rather than
    // about how many months happen to fall inside a horizon.
    private static final LocalDate HORIZON = LocalDate.parse("2027-01-31");

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    InvoiceGenerator generator;

    @Inject
    RenterLedger ledger;

    private static String invoiceOfOtherRenter;

    // ------------------------------------------------------------------ setup

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void twoAgenciesBillTheSameRenterAndAThirdLeaseBelongsToSomeoneElse() {
        send(TestEvents.monthlyLease(LEASE_A, AGENCY_A, RENTER_ONE, "Ama Kouassi",
                LANDLORD_ONE, "2027-01-01", "2027-01-20"));
        send(TestEvents.monthlyLease(LEASE_B, AGENCY_B, RENTER_ONE, "Ama Kouassi",
                LANDLORD_ONE, "2027-01-01", "2027-01-20"));
        send(TestEvents.monthlyLease(LEASE_OTHER, AGENCY_A, RENTER_TWO, "Kofi Mensah",
                LANDLORD_ONE, "2027-01-01", "2027-01-20"));

        // Polled on the test thread rather than awaitility's own: RenterLedger is a
        // request-path service and reasonably expects a request context, which only the
        // JUnit thread has under @QuarkusTest.
        await().pollInSameThread().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            generator.generateThrough(HORIZON);
            assertEquals(2, invoiceCountFor(RENTER_ONE),
                    "one invoice from each agency this renter deals with");
            assertEquals(1, invoiceCountFor(RENTER_TWO));
        });
    }

    // ------------------------------------------------------ what a renter sees

    @Test
    @Order(2)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_ONE))
    void arenterSeesTheirOwnRentAcrossEveryAgency() {
        // The whole reason this is a projection and not a query over Invoice: a renter
        // has no agency, so a tenant-filtered read would correctly return nothing.
        given()
                .when().get("/api/renter/invoices")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(2))
                .body("items.billedByAgency", containsInAnyOrder(AGENCY_A, AGENCY_B));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "renter-two", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_TWO))
    void theOtherRenterSeesOnlyTheirOwn() {
        invoiceOfOtherRenter = given()
                .when().get("/api/renter/invoices")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(1))
                .body("items.billedByAgency", contains(AGENCY_A))
                .extract().path("items[0].invoiceId");

        assertNotNull(invoiceOfOtherRenter);
    }

    @Test
    @Order(4)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_ONE))
    void anotherRentersInvoiceIsAbsentFromTheList() {
        given()
                .when().get("/api/renter/invoices")
                .then()
                .statusCode(200)
                .body("items.invoiceId", not(hasItem(invoiceOfOtherRenter)));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_ONE))
    void anotherRentersInvoiceIsNotFoundRatherThanForbidden() {
        // 404, not 403. A 403 would confirm the invoice exists, which is itself
        // something one renter is not entitled to learn about another.
        given()
                .when().get("/api/renter/invoices/" + invoiceOfOtherRenter)
                .then()
                .statusCode(404)
                .body("code", equalTo("INVOICE_NOT_FOUND"));
    }

    // -------------------------------------------------- and cannot pay it either

    @Test
    @Order(6)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_ONE))
    void arenterCannotPayAnotherRentersInvoice() {
        // The read path and the write path resolve the invoice the same way, through the
        // renter's own projection. Were they to diverge, an invoice nobody could see
        // would still be payable by anyone who guessed its id.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "amount", "150000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "WAVE-RI-9999"))
                .when().post("/api/renter/invoices/" + invoiceOfOtherRenter + "/payments")
                .then()
                .statusCode(404)
                .body("code", equalTo("INVOICE_NOT_FOUND"));
    }

    @Test
    @Order(7)
    @TestSecurity(user = "renter-two", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_TWO))
    void theRightfulRenterCanPayTheSameInvoice() {
        // The mirror image: the refusal above must be about who is asking, not about
        // something else being wrong with the invoice.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "amount", "150000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "WAVE-RI-1000"))
                .when().post("/api/renter/invoices/" + invoiceOfOtherRenter + "/payments")
                .then()
                .statusCode(201)
                .body("status", equalTo("PENDING"));
    }

    // ------------------------------------------------------------ wrong audience

    @Test
    @Order(8)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY_A))
    void anAgentTokenCannotReadARentersLedger() {
        given()
                .when().get("/api/renter/invoices")
                .then().statusCode(403);
    }

    @Test
    @Order(9)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_ONE))
    void arenterTokenCannotReachTheAgencyEndpoints() {
        given()
                .when().get("/api/agency/invoices")
                .then().statusCode(403);

        given()
                .when().get("/api/agency/payouts")
                .then().statusCode(403);
    }

    @Test
    @Order(10)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER_ONE))
    void arenterCannotReadLandlordEarnings() {
        // Renters and landlords are both platform-wide principals with a party_id. Only
        // the role separates them, which is why the role check has to be real.
        given()
                .when().get("/api/landlord/earnings")
                .then().statusCode(403);
    }

    // ---------------------------------------------------------------- helpers

    private void send(String payload) {
        connector.source("lease-events").send(payload);
    }

    /**
     * Counted through the ledger rather than over HTTP, because this runs inside an
     * awaitility poll where no caller identity is established. The isolation assertions
     * that matter all go through the API.
     */
    private long invoiceCountFor(String renterId) {
        return ledger.countInvoicesOf(UUID.fromString(renterId), false);
    }
}
