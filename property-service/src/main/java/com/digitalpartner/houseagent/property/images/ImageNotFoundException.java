package com.digitalpartner.houseagent.property.images;

import java.util.UUID;

/** Also thrown when the image belongs to another agency, or to another house. */
public class ImageNotFoundException extends RuntimeException {

    public ImageNotFoundException(UUID id) {
        super("No image " + id);
    }
}
