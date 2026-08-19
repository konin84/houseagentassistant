package com.digitalpartner.houseagent.property.images;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signs Cloudinary requests so a browser can upload without ever holding the secret.
 *
 * <h2>The algorithm, and why it is written out rather than pulled in</h2>
 *
 * Cloudinary's scheme is: take the parameters to be signed, sort them by name, join
 * them as {@code k=v} with {@code &}, append the API secret, and hash. It is a dozen
 * lines, it is fully specified, and having it here means the one security-relevant
 * calculation in this feature is visible and directly testable against a known vector -
 * which the SDK's version is not.
 *
 * <p>Note what is <em>not</em> signed: {@code file}, {@code cloud_name},
 * {@code resource_type} and {@code api_key} are excluded by Cloudinary's own rules.
 * Everything else that is sent must be signed, or the upload is rejected - so a caller
 * cannot quietly add a parameter the server did not authorise.
 */
@ApplicationScoped
public class CloudinarySignatures {

    @Inject
    CloudinaryConfig config;

    /**
     * The signature for a set of upload parameters.
     *
     * <p>A {@link TreeMap} rather than a sort at the end: the ordering is part of the
     * protocol, not a presentation detail, and making the structure guarantee it removes
     * the possibility of a caller passing an unordered map and getting a signature that
     * silently fails at Cloudinary.
     */
    public String sign(Map<String, String> paramsToSign) {
        String secret = config.apiSecret()
                .orElseThrow(() -> new ImagesNotConfiguredException(
                        "Cannot sign an upload without app.cloudinary.api-secret"));

        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(paramsToSign).entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                // Cloudinary omits empty parameters from the signature. Including them
                // would produce a signature the account cannot reproduce.
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append('&');
            }
            canonical.append(entry.getKey()).append('=').append(entry.getValue());
        }
        canonical.append(secret);

        return hexDigest(canonical.toString());
    }

    private String hexDigest(String value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(config.signatureAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "Unknown Cloudinary signature algorithm '" + config.signatureAlgorithm()
                            + "'; the account setting and this configuration must agree", e);
        }

        byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
