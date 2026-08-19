package com.digitalpartner.houseagent.property.images;

import com.digitalpartner.houseagent.property.domain.HouseImage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ImageDtos {

    /**
     * Everything a browser needs to upload straight to Cloudinary.
     *
     * <p>Contains no secret. {@code signature} is derived from the API secret and these
     * exact parameters, so it authorises this one upload to this one path and nothing
     * else - and it stops being usable when the timestamp ages out.
     *
     * @param uploadUrl where to POST, so the client does not construct it and get the
     *                  API version wrong
     * @param params    the fields to send alongside the file, ready to be copied into a
     *                  multipart form. Given as a map rather than named fields so adding
     *                  a signed parameter later does not break every client.
     */
    public record UploadTicketResponse(
            String uploadUrl,
            String publicId,
            Map<String, String> params,
            Instant expiresAt) {
    }

    /**
     * What the client echoes back once Cloudinary has accepted the file.
     *
     * <p>The public id is checked against the path this agency and house were signed
     * for; anything else is refused. The remaining fields are Cloudinary's own report of
     * the stored asset and are taken on trust - they affect layout, not access.
     */
    public record RegisterImageRequest(
            @NotBlank @Size(max = 512) String publicId,
            Integer width,
            Integer height,
            @Size(max = 16) String format,
            Long bytes,
            /** Makes this the marketplace thumbnail, replacing any existing cover. */
            boolean cover) {
    }

    /**
     * An image as a client sees it.
     *
     * <p>Carries both the public id and rendered URLs. The id is what a client would
     * quote back when reordering or deleting; the URLs are what it puts in an
     * {@code <img>}, and they are derived per request so a transformation can change
     * without touching a single row.
     */
    public record ImageResponse(
            UUID id,
            String publicId,
            String url,
            String thumbnailUrl,
            Integer width,
            Integer height,
            String format,
            Long bytes,
            int position,
            boolean cover,
            Instant uploadedAt) {

        public static ImageResponse from(HouseImage image, CloudinaryUrls urls) {
            return new ImageResponse(
                    image.id,
                    image.cloudinaryPublicId,
                    urls.fullUrl(image.cloudinaryPublicId),
                    urls.thumbnailUrl(image.cloudinaryPublicId),
                    image.width,
                    image.height,
                    image.format,
                    image.bytes,
                    image.position,
                    image.cover,
                    image.uploadedAt);
        }
    }

    private ImageDtos() {
    }
}
