package com.digitalpartner.houseagent.property;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request-body builders for the tests.
 *
 * <p>Uses maps rather than the DTO records so a test can deliberately send a
 * malformed payload that the record would not let it construct.
 */
final class TestHouses {

    /** Party ids used as landlords. Any UUID works; these just read clearly in output. */
    static final String LANDLORD_A = "11111111-1111-1111-1111-111111111111";
    static final String LANDLORD_B = "22222222-2222-2222-2222-222222222222";

    static Map<String, Object> createRequest(String title, String city, int bedrooms, String price) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("street", "12 Rue des Jardins");
        address.put("district", "Cocody");
        address.put("city", city);
        address.put("countryCode", "CI");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("landlordId", LANDLORD_A);
        body.put("title", title);
        body.put("description", "A pleasant house used by the test suite.");
        body.put("address", address);
        body.put("bedrooms", bedrooms);
        body.put("bathrooms", 1);
        body.put("sizeSqm", 90);
        body.put("pricePerMonth", price);
        body.put("currency", "XOF");
        return body;
    }

    static Map<String, Object> createRequest(String title, String city) {
        return createRequest(title, city, 3, "150000.00");
    }

    static Map<String, Object> statusUpdate(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        return body;
    }

    private TestHouses() {
    }
}
