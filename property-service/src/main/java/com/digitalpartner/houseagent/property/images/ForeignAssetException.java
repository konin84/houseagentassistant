package com.digitalpartner.houseagent.property.images;

/**
 * A caller tried to register a public id outside the path they were signed for.
 *
 * <p>This is the check that makes direct-to-Cloudinary upload safe. Without it,
 * registration would accept any public id in the account - including another agency's
 * photographs, which could then be attached to this agency's listing.
 */
public class ForeignAssetException extends RuntimeException {

    public ForeignAssetException(String message) {
        super(message);
    }
}
