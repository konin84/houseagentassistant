package com.digitalpartner.houseagent.payment;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.outbox.OutboxRelay;
import com.digitalpartner.houseagent.payment.service.ArrearsMonitor;
import com.digitalpartner.houseagent.payment.service.InvoiceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rent that has not arrived should be noticed once, not every time a job runs.
 *
 * <p>Lateness is derived from the due date and the clock rather than stored, so this
 * also pins the thing that makes that safe: a sweep is free to run as often as it likes
 * because {@code overdueNotifiedAt} - not the invoice's status - is what decides whether
 * anyone gets told.
 *
 * <p>The sweep is driven with an explicit date rather than by waiting for time to pass,
 * which is the only honest way to test something whose whole subject is dates.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArrearsTest {

    static final String AGENCY = "arrears-agency-a";

    private static final String LEASE = "b1111111-bbbb-1111-bbbb-111111111111";
    private static final String RENTER = "b2222222-bbbb-2222-bbbb-222222222222";
    private static final String LANDLORD = "b3333333-bbbb-3333-bbbb-333333333333";

    // A lease well in the past, so "overdue" is unambiguous and does not depend on when
    // the suite runs. Three monthly periods fall inside the horizon.
    private static final String LEASE_START = "2025-01-01";
    private static final String LEASE_END = "2025-12-31";
    private static final LocalDate HORIZON = LocalDate.parse("2025-03-31");
    private static final LocalDate AFTER_THEY_ARE_ALL_LATE = LocalDate.parse("2025-04-01");

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    InvoiceGenerator generator;

    @Inject
    ArrearsMonitor arrears;

    @Inject
    OutboxRelay relay;

    @Inject
    ObjectMapper objectMapper;

    @Test
    @Order(1)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void threeUnpaidInvoicesInThePast() {
        connector.source("lease-events").send(TestEvents.monthlyLease(
                LEASE, AGENCY, RENTER, "Ama Kouassi", LANDLORD, LEASE_START, LEASE_END));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            generator.generateThrough(HORIZON);
            // At least, not exactly: what this test needs is the three periods that fall
            // before the sweep date. Pinning a total would make it depend on what
            // horizon some other test class happened to bill this lease through.
            assertTrue(invoiceCountForLease() >= 3, "the early periods must be billed");
        });
    }

    @Test
    @Order(2)
    void theSweepAnnouncesEachLateInvoiceOnce() {
        sink().clear();

        arrears.sweepAsOf(AFTER_THEY_ARE_ALL_LATE);
        relay.relayPending();

        List<JsonNode> announced = overdueEventsForLease();
        assertEquals(3, announced.size(), "one announcement per late invoice");

        JsonNode payload = announced.getFirst().get("payload");
        assertEquals(LANDLORD, payload.get("landlordId").asText());
        assertEquals(RENTER, payload.get("renterId").asText());
        assertEquals("Ama Kouassi", payload.get("renterName").asText());
        assertEquals("Villa Cocody 12", payload.get("houseReference").asText());
        assertTrue(payload.get("daysOverdue").asInt() > 0);
    }

    @Test
    @Order(3)
    void runningTheSweepAgainAnnouncesNothingNew() {
        sink().clear();

        // Without overdueNotifiedAt, a job on a six-hour timer would email the same
        // landlord about the same unpaid rent four times a day until they stopped
        // reading any of it.
        arrears.sweepAsOf(AFTER_THEY_ARE_ALL_LATE);
        relay.relayPending();

        assertTrue(overdueEventsForLease().isEmpty(),
                "an invoice already announced must not be announced again");
    }

    @Test
    @Order(4)
    void aninvoiceThatIsNotYetDueIsNotChased() {
        sink().clear();

        // Swept as of a date before any of these invoices were due. Nothing here is
        // late, so nothing should be said about it.
        arrears.sweepAsOf(LocalDate.parse("2024-12-31"));
        relay.relayPending();

        assertTrue(overdueEventsForLease().isEmpty());
    }

    @Test
    @Order(5)
    @TestSecurity(user = "agent-a", roles = Roles.AGENT)
    @OidcSecurity(claims = @Claim(key = "agency_id", value = AGENCY))
    void theAgencyCanSeeItsOwnChaseList() {
        var overdue = given()
                .queryParam("size", 100)
                .when().get("/api/agency/invoices/overdue")
                .then().statusCode(200)
                .extract().jsonPath();

        List<String> leases = overdue.getList("items.leaseId", String.class);
        assertTrue(leases.contains(LEASE), "the late invoices belong on the chase list");

        // Derived on read, so the list is correct even if no sweep has ever run.
        List<Boolean> overdueFlags = overdue.getList("items.overdue", Boolean.class);
        assertFalse(overdueFlags.contains(false), "everything on the chase list is late");
    }

    // ---------------------------------------------------------------- helpers

    private InMemorySink<String> sink() {
        return connector.sink("payment-events");
    }

    private static int invoiceCountForLease() {
        List<String> ids = given()
                .queryParam("size", 100)
                .when().get("/api/agency/invoices")
                .then().statusCode(200)
                .extract().jsonPath().getList("items.findAll { it.leaseId == '" + LEASE + "' }.id",
                        String.class);
        return ids.size();
    }

    /** Only this lease's announcements: other test classes share the database. */
    private List<JsonNode> overdueEventsForLease() {
        List<JsonNode> found = new ArrayList<>();
        for (Message<String> message : sink().received()) {
            try {
                JsonNode envelope = objectMapper.readTree(message.getPayload());
                if ("RentOverdue".equals(envelope.path("eventType").asText())
                        && LEASE.equals(envelope.path("payload").path("leaseId").asText())) {
                    found.add(envelope);
                }
            } catch (Exception e) {
                throw new AssertionError("Relayed payload is not JSON: " + message.getPayload(), e);
            }
        }
        return found;
    }
}
