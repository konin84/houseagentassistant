package com.digitalpartner.houseagent.payment;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.service.InvoiceGenerator;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Taking the same money twice is the failure this service exists to prevent.
 *
 * <p>Payment providers retry their callbacks - that is not an edge case, it is the
 * documented behaviour of every one of them. So every route into a settlement is
 * exercised here twice, and the second time must change nothing.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestSecurity(user = "agent-a", roles = Roles.AGENT)
@OidcSecurity(claims = @Claim(key = "agency_id", value = PaymentIdempotencyTest.AGENCY))
class PaymentIdempotencyTest {

    static final String AGENCY = "idem-agency-a";

    private static final String LEASE = "a1111111-aaaa-1111-aaaa-111111111111";
    private static final String RENTER = "a2222222-aaaa-2222-aaaa-222222222222";
    private static final String LANDLORD = "a3333333-aaaa-3333-aaaa-333333333333";

    private static final LocalDate HORIZON = LocalDate.parse("2028-01-31");

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    InvoiceGenerator generator;

    private static String invoiceId;
    private static String paymentId;

    @Test
    @Order(1)
    void anInvoiceToPay() {
        connector.source("lease-events").send(TestEvents.monthlyLease(
                LEASE, AGENCY, RENTER, "Ama Kouassi", LANDLORD, "2028-01-01", "2028-01-20"));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            generator.generateThrough(HORIZON);
            assertNotNull(invoiceIdOfLease(), "the lease must be replicated and billed");
        });

        invoiceId = invoiceIdOfLease();
    }

    // ------------------------------------------------- the same reference twice

    @Test
    @Order(2)
    void aPaymentIsRecordedOnce() {
        paymentId = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", invoiceId,
                        "amount", "50000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "OM-TX-4242"))
                .when().post("/api/agency/payments")
                .then()
                .statusCode(201)
                .body("status", equalTo("SETTLED"))
                .extract().path("id");

        given()
                .when().get("/api/agency/invoices/" + invoiceId)
                .then()
                .statusCode(200)
                // Part-paid, not paid: 50,000 against 150,000.
                .body("status", equalTo("PARTIALLY_PAID"));
    }

    @Test
    @Order(3)
    void thesameProviderReferenceIsRefusedRatherThanTakenAgain() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", invoiceId,
                        "amount", "50000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "OM-TX-4242"))
                .when().post("/api/agency/payments")
                .then()
                .statusCode(409)
                .body("code", equalTo("PAYMENT_ALREADY_RECORDED"));
    }

    @Test
    @Order(4)
    void theInvoiceIsUnchangedByTheRefusedRetry() {
        // The point of the previous test. A 409 that had already credited the invoice
        // would be worse than no check at all.
        var invoice = given()
                .when().get("/api/agency/invoices/" + invoiceId)
                .then().statusCode(200)
                .extract().jsonPath();

        assertEquals(0, new BigDecimal("50000.00")
                .compareTo(new BigDecimal(invoice.getString("amountPaid"))));
        assertEquals(0, new BigDecimal("100000.00")
                .compareTo(new BigDecimal(invoice.getString("outstanding"))));
    }

    // --------------------------------------------------- confirming twice

    @Test
    @Order(5)
    void confirmingAnAlreadySettledPaymentIsANoOp() {
        // A provider redelivering a confirmation must be told "yes, that one" rather
        // than given an error it will keep retrying against.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("providerReference", "OM-TX-4242"))
                .when().post("/api/agency/payments/" + paymentId + "/settlement")
                .then()
                .statusCode(200)
                .body("status", equalTo("SETTLED"));

        var invoice = given()
                .when().get("/api/agency/invoices/" + invoiceId)
                .then().statusCode(200)
                .extract().jsonPath();

        assertEquals(0, new BigDecimal("50000.00")
                .compareTo(new BigDecimal(invoice.getString("amountPaid"))),
                "a redelivered confirmation must not credit the invoice again");
    }

    // ------------------------------------------------------- paying too much

    @Test
    @Order(6)
    void apaymentLargerThanTheBalanceIsRefused() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", invoiceId,
                        "amount", "100000.01",
                        "method", "MOBILE_MONEY",
                        "providerReference", "OM-TX-5555"))
                .when().post("/api/agency/payments")
                .then()
                .statusCode(409)
                .body("code", equalTo("PAYMENT_STATE_CONFLICT"));
    }

    @Test
    @Order(7)
    void payingTheExactBalanceClosesTheInvoice() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", invoiceId,
                        "amount", "100000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "OM-TX-6666"))
                .when().post("/api/agency/payments")
                .then().statusCode(201);

        given()
                .when().get("/api/agency/invoices/" + invoiceId)
                .then()
                .statusCode(200)
                .body("status", equalTo("PAID"));
    }

    @Test
    @Order(8)
    void aPaidInvoiceTakesNoMoreMoney() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", invoiceId,
                        "amount", "1000.00",
                        "method", "MOBILE_MONEY",
                        "providerReference", "OM-TX-7777"))
                .when().post("/api/agency/payments")
                .then()
                .statusCode(409)
                .body("code", equalTo("PAYMENT_STATE_CONFLICT"));
    }

    // ------------------------------------------------- references are mandatory

    @Test
    @Order(9)
    void anElectronicPaymentWithoutAReferenceIsRefused() {
        // Without a reference there is nothing to be idempotent against, so accepting it
        // would silently give up the guarantee every test above depends on.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "invoiceId", invoiceId,
                        "amount", "1000.00",
                        "method", "MOBILE_MONEY"))
                .when().post("/api/agency/payments")
                .then()
                .statusCode(409)
                .body("code", equalTo("PAYMENT_STATE_CONFLICT"));
    }

    // ---------------------------------------------------------------- helpers

    private static String invoiceIdOfLease() {
        return given()
                .queryParam("size", 100)
                .when().get("/api/agency/invoices")
                .then().statusCode(200)
                .extract().path("items.find { it.leaseId == '" + LEASE + "' }.id");
    }
}
