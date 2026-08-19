package com.digitalpartner.houseagent.common.events;

/**
 * Kafka topic names. One topic per aggregate rather than per event type, so that
 * events about the same house or lease keep their relative order when partitioned
 * by aggregate id.
 */
public final class Topics {

    public static final String HOUSE = "house-events";
    public static final String LEASE = "lease-events";
    public static final String PAYMENT = "payment-events";

    /**
     * Kafka header carrying the agency a message belongs to.
     * <p>
     * A consumer thread has no HTTP request and therefore no JWT, so it cannot infer
     * the agency the way a REST call can. It must read this header and establish the
     * tenant explicitly before touching the database. Forgetting to do so is the most
     * common way multi-tenant systems leak data between customers.
     */
    public static final String AGENCY_HEADER = "x-agency-id";

    private Topics() {
    }
}
