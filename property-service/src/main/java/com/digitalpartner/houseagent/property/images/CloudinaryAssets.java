package com.digitalpartner.houseagent.property.images;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Event;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Destroys assets at Cloudinary once the database has agreed they are gone.
 *
 * <h2>Why this is fired after commit and not inside the transaction</h2>
 *
 * Two reasons, and both are the sort of thing that only shows up in production. An
 * outbound HTTP call inside a transaction holds a database connection open for as long
 * as somebody else's network takes, which under load is how a pool runs dry. And if the
 * transaction then rolls back, the file has already been destroyed while the row that
 * described it still exists.
 *
 * <p>The trade is that a destroy can fail after the row is gone, leaving an asset nobody
 * references. That is the right way round - storage costs money, but an image that
 * outlives its record is recoverable, whereas a record pointing at a file that was
 * deleted under it is not. Failures are logged with the public id so a reconciliation
 * job, or a person, can clean up.
 */
@ApplicationScoped
public class CloudinaryAssets {

    private static final Logger LOG = Logger.getLogger(CloudinaryAssets.class);

    private static final String DESTROY_ENDPOINT =
            "https://api.cloudinary.com/v1_1/%s/image/destroy";

    @Inject
    CloudinaryConfig config;

    @Inject
    CloudinarySignatures signatures;

    @Inject
    Event<AssetDeleted> deletions;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Carries a public id from the committing transaction to the observer below. */
    public record AssetDeleted(String publicId) {
    }

    /**
     * Queues a destroy for after the current transaction commits.
     *
     * <p>Nothing happens if the transaction rolls back, which is the point.
     */
    public void destroyAfterCommit(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        deletions.fire(new AssetDeleted(publicId));
    }

    void onCommitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) AssetDeleted event) {
        if (!config.deleteRemoteAssets()) {
            // Off in tests, where the credentials are fake and the call would be a slow
            // way of failing.
            LOG.debugf("Remote asset deletion disabled; leaving %s in place", event.publicId());
            return;
        }
        if (!config.isConfigured()) {
            return;
        }

        try {
            destroy(event.publicId());
        } catch (Exception e) {
            // Never rethrown. The image is already gone from the gallery and the user's
            // request succeeded; failing here would report an error for work that was
            // done. The public id in this message is what makes the orphan findable.
            LOG.errorf(e, "Could not destroy Cloudinary asset %s; it is now orphaned",
                    event.publicId());
        }
    }

    private void destroy(String publicId) throws Exception {
        long timestamp = Instant.now().getEpochSecond();

        Map<String, String> toSign = new LinkedHashMap<>();
        toSign.put("public_id", publicId);
        toSign.put("timestamp", String.valueOf(timestamp));

        Map<String, String> form = new LinkedHashMap<>(toSign);
        form.put("api_key", config.apiKey().orElseThrow());
        form.put("signature", signatures.sign(toSign));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DESTROY_ENDPOINT.formatted(config.cloudName().orElseThrow())))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Cloudinary returned " + response.statusCode() + ": " + response.body());
        }
    }

    private static String urlEncode(Map<String, String> form) {
        StringJoiner joiner = new StringJoiner("&");
        form.forEach((key, value) -> joiner.add(
                java.net.URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                        + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return joiner.toString();
    }

    @PreDestroy
    void close() {
        http.close();
    }
}
