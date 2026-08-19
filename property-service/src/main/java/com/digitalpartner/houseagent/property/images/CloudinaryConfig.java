package com.digitalpartner.houseagent.property.images;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * Cloudinary account settings.
 *
 * <h2>Why the browser uploads directly and this service never sees the bytes</h2>
 *
 * The obvious design accepts a multipart upload here and forwards it to Cloudinary.
 * That makes every image cross the network twice, holds a several-megabyte buffer per
 * concurrent upload, and turns a stateless service into one whose memory profile is set
 * by how many agents happen to be photographing houses.
 *
 * <p>So the browser posts straight to Cloudinary. This service's only job is to say
 * "yes, that upload is allowed, to exactly this path" by signing it - see
 * {@link CloudinarySignatures} - and to record the resulting public id afterwards.
 *
 * <p>{@link #apiSecret()} signs those requests. It never leaves the server and is never
 * part of a response; only the derived signature is.
 */
@ConfigMapping(prefix = "app.cloudinary")
public interface CloudinaryConfig {

    /** The account's cloud name, which also appears in every delivery URL. */
    Optional<String> cloudName();

    Optional<String> apiKey();

    Optional<String> apiSecret();

    /**
     * Top-level folder for every asset this platform owns.
     *
     * <p>Public ids are built as {@code {rootFolder}/{agencyId}/{houseId}/{uuid}}, and a
     * signature is bound to that exact path. An agency therefore cannot obtain a
     * signature that would overwrite another agency's photograph.
     */
    @WithDefault("houses")
    String rootFolder();

    /**
     * How long a signed upload stays usable.
     *
     * <p>Short on purpose: the signature authorises a write to the account, so a leaked
     * one should stop being useful quickly. Long enough that a slow phone on a poor
     * connection still finishes.
     */
    @WithDefault("10m")
    Duration signatureTtl();

    /**
     * Cloudinary's signing algorithm for this account.
     *
     * <p>Their default is SHA-1, and the signature must match whatever the account is
     * configured for or every upload is rejected. Switch this only after changing the
     * account setting to match.
     */
    @WithDefault("SHA-1")
    String signatureAlgorithm();

    /**
     * Whether deleting an image also destroys the asset at Cloudinary.
     *
     * <p>Off in tests, where the credentials are fake and a real HTTP call would be both
     * pointless and slow.
     */
    @WithDefault("true")
    boolean deleteRemoteAssets();

    /**
     * True when uploads can actually be signed.
     *
     * <p>An unconfigured account is not an error: the rest of the service works
     * perfectly without an image host, and refusing to boot over it would make a house
     * catalogue depend on a photography feature.
     */
    default boolean isConfigured() {
        return cloudName().filter(s -> !s.isBlank()).isPresent()
                && apiKey().filter(s -> !s.isBlank()).isPresent()
                && apiSecret().filter(s -> !s.isBlank()).isPresent();
    }
}
