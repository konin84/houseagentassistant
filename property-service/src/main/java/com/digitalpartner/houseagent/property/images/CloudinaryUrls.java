package com.digitalpartner.houseagent.property.images;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds delivery URLs from a stored public id.
 *
 * <h2>Why URLs are derived and never stored</h2>
 *
 * A URL bakes in the cloud name, the transformation and the delivery host. Store it and
 * every one of those becomes impossible to change without rewriting every row - moving
 * account, adding a CDN, or simply deciding thumbnails should be 400px rather than 300px
 * turns into a migration. The public id is the durable fact; everything else is a
 * rendering decision that belongs at read time.
 *
 * <p>The transformations below also do real work. {@code f_auto} serves AVIF or WebP to
 * browsers that accept them and JPEG to those that do not, and {@code q_auto} picks a
 * quality per image rather than a fixed one. For a marketplace grid on a phone over
 * mobile data in Abidjan, that is the difference between a page that loads and one that
 * does not.
 */
@ApplicationScoped
public class CloudinaryUrls {

    /** Marketplace grid: cropped to a consistent card shape. */
    private static final String THUMBNAIL = "c_fill,g_auto,w_400,h_300,q_auto,f_auto";

    /** House detail: bounded, never upscaled, aspect ratio preserved. */
    private static final String FULL = "c_limit,w_1600,q_auto,f_auto";

    @Inject
    CloudinaryConfig config;

    /** A card-sized image, or null when there is no photo or no account configured. */
    public String thumbnailUrl(String publicId) {
        return url(publicId, THUMBNAIL);
    }

    /** A full-size image, or null when there is no photo or no account configured. */
    public String fullUrl(String publicId) {
        return url(publicId, FULL);
    }

    private String url(String publicId, String transformation) {
        if (publicId == null || publicId.isBlank() || config.cloudName().isEmpty()) {
            // Null rather than a broken URL or a placeholder. A client can tell the
            // difference between "no photo" and "a photo that will 404"; a string that
            // looks like a URL but resolves to nothing is worse than neither.
            return null;
        }
        return "https://res.cloudinary.com/" + config.cloudName().get()
                + "/image/upload/" + transformation + "/" + publicId;
    }
}
