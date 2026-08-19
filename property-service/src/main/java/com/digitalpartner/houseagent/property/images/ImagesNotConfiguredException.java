package com.digitalpartner.houseagent.property.images;

/**
 * Uploads were asked for on a deployment with no Cloudinary account.
 *
 * <p>Deliberately not a startup failure. A catalogue of houses is useful without
 * photographs, and refusing to boot would make the whole service depend on an
 * integration only one endpoint needs.
 */
public class ImagesNotConfiguredException extends RuntimeException {

    public ImagesNotConfiguredException(String message) {
        super(message);
    }
}
