package com.digitalpartner.houseagent.lease;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Request-body builders for the lease tests. */
final class TestLeases {

    static Map<String, Object> signRequest(String houseId, String landlordId, String renterId,
                                           String renterName, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("houseId", houseId);
        body.put("renterId", renterId);
        body.put("renterName", renterName);
        body.put("renterPhone", "+225 07 00 00 00");
        body.put("landlordId", landlordId);
        body.put("houseReference", "Villa Cocody 12");
        body.put("rentAmount", "150000.00");
        body.put("currency", "XOF");
        body.put("cadence", "MONTHLY");
        body.put("dueDayOfMonth", 5);
        body.put("depositAmount", "300000.00");
        body.put("startDate", start);
        body.put("endDate", end);
        return body;
    }

    static Map<String, Object> signRequest(String houseId, String landlordId,
                                           String start, String end) {
        return signRequest(houseId, landlordId, UUID.randomUUID().toString(),
                "Test Renter", start, end);
    }

    static Map<String, Object> endRequest(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return body;
    }

    private TestLeases() {
    }
}
