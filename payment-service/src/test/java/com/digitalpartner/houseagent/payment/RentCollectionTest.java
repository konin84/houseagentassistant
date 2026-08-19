package com.digitalpartner.houseagent.payment;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.outbox.OutboxRelay;
import com.digitalpartner.houseagent.payment.service.InvoiceGenerator;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole money loop, in the order it happens: a lease is signed elsewhere, invoices
 * appear, a renter pays one, the landlord is credited, and an event goes out saying so.
 *
 * <p>Told as one ordered story rather than split into isolated cases, because the
 * interesting failures are in the joins - an invoice that is raised but invisible to
 * the renter, a payment that settles without crediting the invoice, a commission that
 * the payout and the event disagree about.
 *
 * <p>Nothing here waits on a clock. Invoice generation and the outbox relay are driven
 * explicitly, and the test profile pushes their timers out to 24h, so a failure means a
 * bug rather than a slow machine.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RentCollectionTest {

    static final String AGENCY = "pay-agency-a";
    static final String RENTER = "e1111111-1111-1111-1111-111111111111";
    static final String LANDLORD = "e2222222-2222-2222-2222-222222222222";

    private static final String LEASE = "e3333333-3333-3333-3333-333333333333";

    /** Fixed dates, so the number of invoices does not depend on when the test runs. */
    private static final LocalDate HORIZON = LocalDate.parse("2026-03-31");
    private static final String LEASE_START = "2026-01-01";
    private static final String LEASE_END = "2026-12-31";

    private static final BigDecimal RENT = new BigDecimal("150000.00");

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    InvoiceGenerator generator;

    @Inject
    OutboxRelay relay;

    @Inject
    ObjectMapper objectMapper;

    private static String januaryInvoice;
    private static String februaryInvoice;
    private static String firstPaymentId;

    // ------------------------------------------------- a lease becomes invoices

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void aSignedLeaseIsBilledWithoutCallingLeaseService() {
        connector.source("lease-events").send(TestEvents.monthlyLease(
                LEASE, AGENCY, RENTER, "Ama Kouassi", LANDLORD, LEASE_START, LEASE_END));

        // The consumer runs on another thread. Generation is idempotent, so driving it
        // repeatedly until the replica has landed is safe.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            generator.generateThrough(HORIZON);
            assertEquals(3, agencyInvoiceCount(),
                    "January, February and March fall within the horizon");
        });

        januaryInvoice = invoiceIdForPeriod("2026-01-05");
        februaryInvoice = invoiceIdForPeriod("2026-02-05");
        assertNotNull(januaryInvoice);
        assertNotNull(februaryInvoice);
    }

    @Test
    @Order(2)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void generationIsSafeToRepeat() {
        // The generator re-derives the whole schedule every run. Without the unique
        // index on (lease_id, period_start) this would bill the renter three times over.
        assertEquals(0, generator.generateThrough(HORIZON));
        assertEquals(3, agencyInvoiceCount());
    }

    @Test
    @Order(3)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER))
    void theRenterSeesWhatTheyOwe() {
        given()
                .when().get("/api/renter/invoices")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(3))
                .body("items[0].billedByAgency", equalTo(AGENCY))
                .body("items[0].houseReference", equalTo("Villa Cocody 12"));

        // Three months at 150,000 each. The renter's balance is one number, not a sum
        // the client has to compute.
        BigDecimal owed = money(given()
                .when().get("/api/renter/balance")
                .then().statusCode(200)
                .extract().jsonPath(), "amount");

        assertEquals(0, new BigDecimal("450000.00").compareTo(owed), "owed " + owed);
    }

    // ------------------------------------------------------ paying, in two steps

    @Test
    @Order(4)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER))
    void initiatingAPaymentDoesNotYetPayAnything() {
        firstPaymentId = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "amount", "150000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "WAVE-TX-0001"))
                .when().post("/api/renter/invoices/" + januaryInvoice + "/payments")
                .then()
                .statusCode(201)
                // Initiating a mobile money push is not the money arriving.
                .body("status", equalTo("PENDING"))
                .extract().path("id");

        given()
                .when().get("/api/renter/invoices/" + januaryInvoice)
                .then()
                .statusCode(200)
                .body("status", equalTo("DUE"))
                .body("amountPaid", equalTo(0.00f));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void confirmingTheProviderCallbackPaysTheInvoice() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("providerReference", "WAVE-TX-0001"))
                .when().post("/api/agency/payments/" + firstPaymentId + "/settlement")
                .then()
                .statusCode(200)
                .body("status", equalTo("SETTLED"));

        given()
                .when().get("/api/agency/invoices/" + januaryInvoice)
                .then()
                .statusCode(200)
                .body("status", equalTo("PAID"))
                .body("outstanding", equalTo(0.00f));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "renter-one", roles = Roles.RENTER)
    @OidcSecurity(claims = @Claim(key = "party_id", value = RENTER))
    void theRentersBalanceDropsByWhatTheyPaid() {
        BigDecimal owed = money(given()
                .when().get("/api/renter/balance")
                .then().statusCode(200)
                .extract().jsonPath(), "amount");

        assertEquals(0, new BigDecimal("300000.00").compareTo(owed), "owed " + owed);
    }

    // ------------------------------------------- what the rest of the platform hears

    @Test
    @Order(7)
    void settlementIsAnnouncedOnceTheRelayRuns() {
        sink().clear();
        assertTrue(relay.relayPending() >= 1);

        JsonNode envelope = lastEventOfType("PaymentSettled");
        assertNotNull(envelope, "settling rent must announce it");
        assertEquals(AGENCY, envelope.get("agencyId").asText());

        JsonNode payload = envelope.get("payload");
        assertEquals(LANDLORD, payload.get("landlordId").asText());
        assertEquals("Ama Kouassi", payload.get("renterName").asText());
        assertEquals("Villa Cocody 12", payload.get("houseReference").asText());

        // No agency has configured collection yet, so the default applies: the platform
        // holds nothing, takes nothing, and the landlord's net is the full rent.
        assertEquals(0, BigDecimal.ZERO.compareTo(payload.get("commissionAmount").decimalValue()));
        assertEquals(0, RENT.compareTo(payload.get("netAmount").decimalValue()));
    }

    @Test
    @Order(8)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD))
    void theLandlordSeesTheMoneyArrive() {
        given()
                .when().get("/api/landlord/earnings")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(1))
                .body("items[0].collectedByAgency", equalTo(AGENCY))
                .body("items[0].renterName", equalTo("Ama Kouassi"))
                .body("items[0].houseReference", equalTo("Villa Cocody 12"));
    }

    @Test
    @Order(9)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void underDirectSettlementThereIsNoPayoutToMake() {
        // The renter paid the landlord; the platform never held the money. The absence
        // of a payout row is the record that nothing is owed onward.
        given()
                .when().get("/api/agency/payouts")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(0));
    }

    // ------------------------------------------------ the other settlement model

    @Test
    @Order(10)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anAgencyCanSwitchToCollectingRentItself() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("mode", "PLATFORM_COLLECTS", "commissionBps", 750))
                .when().put("/api/agency/settlement-config")
                .then()
                .statusCode(200)
                .body("mode", equalTo("PLATFORM_COLLECTS"))
                .body("commissionBps", equalTo(750));
    }

    @Test
    @Order(11)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void cashTakenAtTheOfficeSettlesImmediatelyAndAccruesAPayout() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", februaryInvoice,
                        "amount", "150000.00",
                        // Cash has no provider and no callback, so there is nothing to
                        // confirm and no reference to be idempotent against.
                        "method", "CASH"))
                .when().post("/api/agency/payments")
                .then()
                .statusCode(201)
                .body("status", equalTo("SETTLED"));

        var payouts = given()
                .when().get("/api/agency/payouts")
                .then().statusCode(200)
                .extract().jsonPath();

        assertEquals(1, (int) payouts.getInt("totalItems"));
        // 7.5% of 150,000 is 11,250, leaving 138,750 for the landlord.
        assertEquals(0, new BigDecimal("11250.00")
                .compareTo(money(payouts, "items[0].commissionAmount")));
        assertEquals(0, new BigDecimal("138750.00")
                .compareTo(money(payouts, "items[0].netAmount")));
    }

    @Test
    @Order(12)
    void theEventAndThePayoutAgreeOnTheArithmetic() {
        sink().clear();
        relay.relayPending();

        JsonNode payload = lastEventOfType("PaymentSettled").get("payload");

        // Carried on the event rather than left to the consumer to compute, so an email
        // and a payout can never disagree about what the landlord is owed.
        assertEquals(0, new BigDecimal("11250.00")
                .compareTo(payload.get("commissionAmount").decimalValue()));
        assertEquals(0, new BigDecimal("138750.00")
                .compareTo(payload.get("netAmount").decimalValue()));
    }

    @Test
    @Order(13)
    @TestSecurity(user = "landlord-one", roles = Roles.LANDLORD)
    @OidcSecurity(claims = @Claim(key = "party_id", value = LANDLORD))
    void theLandlordsTotalIsNetOfCommission() {
        BigDecimal total = money(given()
                .when().get("/api/landlord/earnings/total")
                .then().statusCode(200)
                .extract().jsonPath(), "amount");

        // 150,000 received in full, then 138,750 after the agency's cut.
        assertEquals(0, new BigDecimal("288750.00").compareTo(total), "total " + total);
    }

    @Test
    @Order(14)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void aCommissionCannotBeChargedOnMoneyThePlatformNeverHolds() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("mode", "DIRECT_TO_LANDLORD", "commissionBps", 500))
                .when().put("/api/agency/settlement-config")
                .then()
                // Agents cannot set commercial terms at all - that is AGENCY_ADMIN work.
                .statusCode(403);
    }

    @Test
    @Order(15)
    @TestSecurity(user = "admin-a", roles = Roles.AGENCY_ADMIN)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void anIncoherentSettlementPolicyIsRefused() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("mode", "DIRECT_TO_LANDLORD", "commissionBps", 500))
                .when().put("/api/agency/settlement-config")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_SETTLEMENT_CONFIG"));
    }

    // ---------------------------------------------------------------- helpers

    private InMemorySink<String> sink() {
        return connector.sink("payment-events");
    }

    /**
     * Reads money out of a response as text before making it a BigDecimal.
     *
     * <p>Rest-assured would otherwise hand back a float, which is the one thing a
     * payments test should not compare amounts as.
     */
    private static BigDecimal money(io.restassured.path.json.JsonPath json, String path) {
        String raw = json.getString(path);
        return raw == null ? null : new BigDecimal(raw);
    }

    private static int agencyInvoiceCount() {
        return given()
                .when().get("/api/agency/invoices")
                .then().statusCode(200)
                .extract().path("totalItems");
    }

    private static String invoiceIdForPeriod(String periodStart) {
        return given()
                .queryParam("size", 100)
                .when().get("/api/agency/invoices")
                .then().statusCode(200)
                .extract().path("items.find { it.periodStart == '" + periodStart + "' }.id");
    }

    private JsonNode lastEventOfType(String eventType) {
        List<? extends Message<String>> received = sink().received();
        JsonNode found = null;
        for (Message<String> message : received) {
            try {
                JsonNode envelope = objectMapper.readTree(message.getPayload());
                if (eventType.equals(envelope.path("eventType").asText())) {
                    found = envelope;
                }
            } catch (Exception e) {
                throw new AssertionError("Relayed payload is not JSON: " + message.getPayload(), e);
            }
        }
        return found;
    }
}
