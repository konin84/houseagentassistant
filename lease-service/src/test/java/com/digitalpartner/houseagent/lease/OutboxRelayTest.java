package com.digitalpartner.houseagent.lease;

import com.digitalpartner.houseagent.common.events.Topics;
import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.lease.outbox.OutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbox is what stops a crash between "lease committed" and "event published"
 * from leaving a let house advertised forever, so the properties worth asserting are
 * about the join between the two rather than about Kafka.
 *
 * <p>The relay is driven explicitly here instead of by its timer - the test profile
 * sets the poll interval to 24h - so these assertions are deterministic rather than
 * dependent on when a scheduler happened to fire.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestSecurity(user = "agent-a", roles = Roles.AGENT)
@OidcSecurity(claims = @Claim(key = "agency_id", value = OutboxRelayTest.AGENCY))
class OutboxRelayTest {

    static final String AGENCY = "outbox-agency-a";

    private static final String LANDLORD = "cccccccc-1111-1111-1111-cccccccccccc";
    private static final String HOUSE = UUID.randomUUID().toString();

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    OutboxRelay relay;

    @Inject
    ObjectMapper objectMapper;

    private static String leaseId;

    @Test
    @Order(1)
    void signingALeaseQueuesAnEventThatTheRelayPublishes() {
        // Other test classes may have left rows behind; drain them so what follows is
        // only this test's doing.
        relay.relayPending();
        sink().clear();

        leaseId = given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-01-01", "2026-12-31"))
                .when().post("/api/agency/leases")
                .then().statusCode(201)
                .extract().path("id");

        // Nothing is published by the write path itself. Until the relay runs, the
        // event exists only as a committed row.
        assertEquals(0, sink().received().size(),
                "the lease write path must not publish to Kafka directly");

        assertEquals(1, relay.relayPending());

        List<? extends Message<String>> published = sink().received();
        assertEquals(1, published.size());

        JsonNode envelope = parse(published.getFirst().getPayload());
        assertEquals("LeaseSigned", envelope.get("eventType").asText());
        assertEquals(AGENCY, envelope.get("agencyId").asText());
        assertEquals(leaseId, envelope.get("payload").get("leaseId").asText());
        assertEquals(HOUSE, envelope.get("payload").get("houseId").asText());
    }

    @Test
    @Order(2)
    void theMessageIsKeyedByLeaseAndCarriesItsAgencyInAHeader() {
        // The key is what keeps two events about the same lease on one partition, and
        // therefore in order. Without it, LeaseEnded could overtake LeaseSigned and a
        // let house would end up advertised.
        var metadata = sink().received().getFirst()
                .getMetadata(OutgoingKafkaRecordMetadata.class)
                .orElseThrow();

        assertEquals(leaseId, metadata.getKey());

        var header = metadata.getHeaders().lastHeader(Topics.AGENCY_HEADER);
        assertNotNull(header, "infrastructure must be able to route without deserialising");
        assertEquals(AGENCY, new String(header.value(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(3)
    void arelayedEventIsNotSentAgain() {
        sink().clear();

        // published_at is what makes the hand-off idempotent. Were it not written, every
        // poll would republish the entire history.
        assertEquals(0, relay.relayPending());
        assertEquals(0, sink().received().size());
    }

    @Test
    @Order(4)
    void aRejectedSigningAnnouncesNothing() {
        sink().clear();

        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.signRequest(HOUSE, LANDLORD, "2026-06-01", "2027-05-31"))
                .when().post("/api/agency/leases")
                .then().statusCode(409);

        // This is the whole point of writing the event inside the lease transaction. The
        // insert was rolled back, so there is no event to relay - property-service is
        // never told about a lease that does not exist.
        assertEquals(0, relay.relayPending());
        assertEquals(0, sink().received().size());
    }

    @Test
    @Order(5)
    void theLifecycleIsPublishedInOrder() {
        sink().clear();

        given()
                .contentType(ContentType.JSON)
                .when().post("/api/agency/leases/" + leaseId + "/activation")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body(TestLeases.endRequest("Tenancy completed"))
                .when().post("/api/agency/leases/" + leaseId + "/termination")
                .then().statusCode(200);

        assertEquals(2, relay.relayPending());

        List<String> types = sink().received().stream()
                .map(m -> parse(m.getPayload()).get("eventType").asText())
                .toList();

        // Ordered by created_at, so property-service sees OCCUPIED before AVAILABLE
        // rather than the other way round.
        assertEquals(List.of("LeaseActivated", "LeaseEnded"), types);
    }

    @Test
    @Order(6)
    void everyRelayedEventCarriesTheAgencyItBelongsTo() {
        // A consumer has no JWT and can only learn the agency from the message. An event
        // published without one cannot be applied to anything, and would silently do
        // nothing on the far side.
        assertTrue(sink().received().stream()
                        .map(m -> parse(m.getPayload()).get("agencyId"))
                        .allMatch(node -> node != null && AGENCY.equals(node.asText())),
                "every message must name its agency");
    }

    // ---------------------------------------------------------------- helpers

    private InMemorySink<String> sink() {
        return connector.sink("lease-events");
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new AssertionError("Relayed payload is not valid JSON: " + payload, e);
        }
    }
}
