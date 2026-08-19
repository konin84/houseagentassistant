package com.digitalpartner.houseagent.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Lease events as lease-service actually publishes them - a full envelope with the
 * payload inside, rather than a shape invented for the test.
 */
final class TestEvents {

    static String leaseSigned(String leaseId, String agencyId, String renterId, String renterName,
                              String landlordId, String houseReference, String rentAmount,
                              String cadence, int dueDayOfMonth, String startDate, String endDate) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "LeaseSigned",
                  "agencyId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "leaseId": "%s",
                    "houseId": "%s",
                    "agencyId": "%s",
                    "renterId": "%s",
                    "renterName": "%s",
                    "landlordId": "%s",
                    "houseReference": "%s",
                    "rentAmount": %s,
                    "currency": "XOF",
                    "cadence": "%s",
                    "dueDayOfMonth": %d,
                    "startDate": "%s",
                    "endDate": %s,
                    "occurredAt": "%s"
                  }
                }
                """.formatted(
                UUID.randomUUID(), agencyId, Instant.now(),
                leaseId, UUID.randomUUID(), agencyId,
                renterId, renterName, landlordId, houseReference,
                rentAmount, cadence, dueDayOfMonth, startDate,
                endDate == null ? "null" : "\"" + endDate + "\"",
                Instant.now());
    }

    /** A monthly lease on the usual terms, which is what most of the tests need. */
    static String monthlyLease(String leaseId, String agencyId, String renterId, String renterName,
                               String landlordId, String startDate, String endDate) {
        return leaseSigned(leaseId, agencyId, renterId, renterName, landlordId,
                "Villa Cocody 12", "150000.00", "MONTHLY", 5, startDate, endDate);
    }

    static String leaseEnded(String leaseId, String agencyId) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "LeaseEnded",
                  "agencyId": "%s",
                  "occurredAt": "%s",
                  "payload": {
                    "leaseId": "%s",
                    "houseId": "%s",
                    "agencyId": "%s",
                    "reason": "Tenancy completed",
                    "occurredAt": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), agencyId, Instant.now(),
                leaseId, UUID.randomUUID(), agencyId, Instant.now());
    }

    private TestEvents() {
    }
}
