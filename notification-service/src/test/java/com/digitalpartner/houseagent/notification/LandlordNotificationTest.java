package com.digitalpartner.houseagent.notification;

import com.digitalpartner.houseagent.notification.service.ContactDirectory;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Tenants make payments, landlords should be notified by email" - this is the test
 * that says so.
 *
 * <p>Asserts on the message a landlord would actually have received rather than on the
 * fact that a send was attempted. An email that arrives quoting two UUIDs at someone is
 * a delivered notification and a useless one.
 *
 * <p>Runs against Quarkus's mock mailbox, so no SMTP server is involved and nobody is
 * emailed by a test run.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LandlordNotificationTest {

    private static final String LANDLORD = "c1111111-cccc-1111-cccc-111111111111";
    private static final String LANDLORD_EMAIL = "landlord@example.ci";

    private static final String RENTER = "c2222222-cccc-2222-cccc-222222222222";
    private static final String RENTER_EMAIL = "renter@example.ci";

    private static final String SILENT = "c3333333-cccc-3333-cccc-333333333333";
    private static final String UNKNOWN = "c4444444-cccc-4444-cccc-444444444444";

    private static final String AGENCY = "notif-agency-a";

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    MockMailbox mailbox;

    @Inject
    ContactDirectory contacts;

    @BeforeEach
    void clearMailbox() {
        mailbox.clear();
    }

    // ------------------------------------------------------------------ setup

    @Test
    @Order(1)
    void theAgencyOnboardsTheLandlordsAddress() {
        contacts.upsert(UUID.fromString(LANDLORD), LANDLORD_EMAIL, "M. Konan", "fr", null, null);
        contacts.upsert(UUID.fromString(RENTER), RENTER_EMAIL, "Ama Kouassi", "fr", null, null);

        assertTrue(contacts.find(UUID.fromString(LANDLORD)).isPresent());
    }

    // ------------------------------------------------- the requirement itself

    @Test
    @Order(2)
    void aSettledPaymentEmailsTheLandlord() {
        send(TestEvents.paymentSettled(UUID.randomUUID().toString(), AGENCY, LANDLORD,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "11250.00", "138750.00"));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(1, mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).size()));

        var mail = mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).getFirst();

        assertTrue(mail.getSubject().contains("Villa Cocody 12"),
                "the subject should name the property: " + mail.getSubject());

        String body = mail.getText();
        assertTrue(body.contains("Ama Kouassi"), "the landlord is told who paid:\n" + body);
        assertTrue(body.contains("150000.00"), "and how much arrived:\n" + body);
        assertTrue(body.contains("11250.00"), "and what the agency kept:\n" + body);
        assertTrue(body.contains("138750.00"), "and what is theirs:\n" + body);
    }

    @Test
    @Order(3)
    void theRenterIsNotEmailedAboutSomeoneElsesRentArriving() {
        // A settlement is the landlord's news. The renter already knows they paid.
        assertEquals(0, mailbox.getMailMessagesSentTo(RENTER_EMAIL).size());
    }

    // ---------------------------------------------------------- redelivery

    @Test
    @Order(4)
    void thesameEventDeliveredTwiceEmailsOnce() {
        String eventId = UUID.randomUUID().toString();
        String event = TestEvents.paymentSettled(eventId, AGENCY, LANDLORD,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "0.00", "150000.00");

        send(event);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(1, mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).size()));

        // Kafka guarantees at-least-once, so this happens in production, not just here.
        send(event);

        // Wait long enough that a second email would have arrived if one were coming.
        await().during(2, TimeUnit.SECONDS)
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertEquals(1, mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).size(),
                                "a redelivered event must not email the landlord again"));
    }

    // ------------------------------------------------------- arrears, two ways

    @Test
    @Order(5)
    void overdueRentTellsBothTheRenterAndTheLandlord() {
        send(TestEvents.rentOverdue(UUID.randomUUID().toString(), AGENCY, RENTER, LANDLORD,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", 12));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(1, mailbox.getMailMessagesSentTo(RENTER_EMAIL).size());
            assertEquals(1, mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).size());
        });

        // One event, two people, two different messages - the renter is asked to pay,
        // the landlord is told it is being chased.
        String toRenter = mailbox.getMailMessagesSentTo(RENTER_EMAIL).getFirst().getText();
        String toLandlord = mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).getFirst().getText();

        assertTrue(toRenter.contains("régulariser"), "the renter is asked to settle it");
        assertTrue(toLandlord.contains("recouvrement"), "the landlord is told it is in hand");
        assertTrue(toRenter.contains("12") && toLandlord.contains("12"),
                "both are told how late it is");
    }

    // ------------------------------------------------------------ preferences

    @Test
    @Order(6)
    void alandlordWhoOptedOutIsNotEmailed() {
        contacts.upsert(UUID.fromString(SILENT), "silent@example.ci", "Mme Silencieuse",
                "fr", false, false);

        send(TestEvents.paymentSettled(UUID.randomUUID().toString(), AGENCY, SILENT,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "0.00", "150000.00"));

        await().during(2, TimeUnit.SECONDS)
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertEquals(0, mailbox.getMailMessagesSentTo("silent@example.ci").size()));
    }

    @Test
    @Order(7)
    void apartyWithNoContactDetailsIsSkippedRatherThanFailing() {
        // An agency may simply not have onboarded this landlord yet. That must not stall
        // the channel for every well-formed event queued behind it.
        send(TestEvents.paymentSettled(UUID.randomUUID().toString(), AGENCY, UNKNOWN,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "0.00", "150000.00"));

        send(TestEvents.paymentSettled(UUID.randomUUID().toString(), AGENCY, LANDLORD,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "0.00", "150000.00"));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(1, mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).size(),
                        "the message behind the unreachable one still goes out"));
    }

    @Test
    @Order(8)
    void anUnparseableEventDoesNotBlockTheChannel() {
        send("this is not json");
        send(TestEvents.paymentSettled(UUID.randomUUID().toString(), AGENCY, LANDLORD,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "0.00", "150000.00"));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(1, mailbox.getMailMessagesSentTo(LANDLORD_EMAIL).size()));
    }

    // ---------------------------------------------------------------- language

    @Test
    @Order(9)
    void alandlordWhoReadsEnglishGetsEnglish() {
        String anglophone = "c5555555-cccc-5555-cccc-555555555555";
        contacts.upsert(UUID.fromString(anglophone), "anglo@example.ci", "Mr Mensah",
                "en", null, null);

        send(TestEvents.paymentSettled(UUID.randomUUID().toString(), AGENCY, anglophone,
                "Ama Kouassi", "Villa Cocody 12", "150000.00", "0.00", "150000.00"));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(1, mailbox.getMailMessagesSentTo("anglo@example.ci").size()));

        var mail = mailbox.getMailMessagesSentTo("anglo@example.ci").getFirst();
        assertTrue(mail.getSubject().startsWith("Rent received"),
                "locale is a functional field, not a decorative one: " + mail.getSubject());
    }

    // ---------------------------------------------------------------- helpers

    private void send(String payload) {
        connector.source("payment-events").send(payload);
    }
}
