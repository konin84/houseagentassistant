package com.digitalpartner.houseagent.notification;

import java.time.Instant;

/**
 * Payment events as payment-service actually publishes them - a full envelope with the
 * payload inside, rather than a shape invented for the test.
 *
 * <p>The event id is passed in rather than generated, because redelivering the
 * <em>same</em> event is the behaviour most of these tests are about.
 */
final class TestEvents {

    static String paymentSettled(String eventId, String agencyId, String landlordId,
                                 String renterName, String houseReference,
                                 String amount, String commission, String net) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "PaymentSettled",
                  "agencyId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "paymentId": "11111111-0000-0000-0000-000000000001",
                    "invoiceId": "11111111-0000-0000-0000-000000000002",
                    "leaseId": "11111111-0000-0000-0000-000000000003",
                    "agencyId": "%s",
                    "renterId": "11111111-0000-0000-0000-000000000004",
                    "renterName": "%s",
                    "landlordId": "%s",
                    "houseReference": "%s",
                    "amount": %s,
                    "commissionAmount": %s,
                    "netAmount": %s,
                    "currency": "XOF",
                    "periodStart": "2026-01-05",
                    "periodEnd": "2026-02-05",
                    "providerReference": "WAVE-TX-0001",
                    "settledAt": "%s"
                  }
                }
                """.formatted(eventId, agencyId, Instant.now(), agencyId, renterName,
                landlordId, houseReference, amount, commission, net, Instant.now());
    }

    static String rentOverdue(String eventId, String agencyId, String renterId,
                              String landlordId, String renterName, String houseReference,
                              String amountDue, int daysOverdue) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "RentOverdue",
                  "agencyId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "invoiceId": "22222222-0000-0000-0000-000000000001",
                    "leaseId": "22222222-0000-0000-0000-000000000002",
                    "agencyId": "%s",
                    "renterId": "%s",
                    "renterName": "%s",
                    "landlordId": "%s",
                    "houseReference": "%s",
                    "amountDue": %s,
                    "currency": "XOF",
                    "dueDate": "2026-01-05",
                    "daysOverdue": %d,
                    "occurredAt": "%s"
                  }
                }
                """.formatted(eventId, agencyId, Instant.now(), agencyId, renterId,
                renterName, landlordId, houseReference, amountDue, daysOverdue, Instant.now());
    }

    private TestEvents() {
    }
}
