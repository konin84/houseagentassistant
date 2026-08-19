package com.digitalpartner.houseagent.property.images;

import com.digitalpartner.houseagent.property.domain.House;
import com.digitalpartner.houseagent.property.domain.HouseImage;
import com.digitalpartner.houseagent.property.images.ImageDtos.ImageResponse;
import com.digitalpartner.houseagent.property.images.ImageDtos.RegisterImageRequest;
import com.digitalpartner.houseagent.property.images.ImageDtos.UploadTicketResponse;
import com.digitalpartner.houseagent.property.service.HouseNotFoundException;
import com.digitalpartner.houseagent.property.service.ListingProjector;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The gallery of one house: authorising uploads, recording them, and ordering them.
 *
 * <h2>Two steps, and why the second one is a security check</h2>
 *
 * Uploading is a round trip the browser makes to Cloudinary without this service. That
 * only works if the browser can prove the upload is allowed, which is what
 * {@link #requestUpload} issues - a signature bound to one exact public id under
 * {@code {root}/{agencyId}/{houseId}/}.
 *
 * <p>{@link #register} then has to assume the caller is hostile, because by then the
 * caller is holding an arbitrary string. If it accepted any public id, an agency could
 * name a competitor's photograph and attach it to its own listing. So registration
 * re-derives the prefix from the caller's own token and refuses anything outside it -
 * the same value the signature was bound to, checked twice on purpose.
 */
@ApplicationScoped
public class HouseImageService {

    private static final String UPLOAD_ENDPOINT =
            "https://api.cloudinary.com/v1_1/%s/image/upload";

    @Inject
    CloudinaryConfig config;

    @Inject
    CloudinarySignatures signatures;

    @Inject
    CloudinaryUrls urls;

    @Inject
    CloudinaryAssets assets;

    @Inject
    ListingProjector listings;

    // -------------------------------------------------------------- uploading

    /**
     * Authorises one upload for one house.
     *
     * <p>Loading the house first is not a formality: it runs through the tenant filter,
     * so an agency cannot obtain a signature for a house that is not theirs, and the
     * 404 for a foreign house is the same 404 as for a missing one.
     */
    @Transactional
    public UploadTicketResponse requestUpload(UUID houseId) {
        if (!config.isConfigured()) {
            throw new ImagesNotConfiguredException(
                    "No Cloudinary account is configured, so uploads cannot be signed");
        }

        House house = load(houseId);
        String publicId = pathFor(house) + UUID.randomUUID();
        long timestamp = Instant.now().getEpochSecond();

        // Exactly the parameters Cloudinary will be sent, minus the ones it excludes
        // from signing by its own rules: file, cloud_name, resource_type and api_key.
        Map<String, String> toSign = new LinkedHashMap<>();
        toSign.put("public_id", publicId);
        toSign.put("timestamp", String.valueOf(timestamp));

        Map<String, String> params = new LinkedHashMap<>(toSign);
        params.put("api_key", config.apiKey().orElseThrow());
        params.put("signature", signatures.sign(toSign));

        return new UploadTicketResponse(
                UPLOAD_ENDPOINT.formatted(config.cloudName().orElseThrow()),
                publicId,
                params,
                Instant.ofEpochSecond(timestamp).plus(config.signatureTtl()));
    }

    /** Records an asset the browser has already uploaded. */
    @Transactional
    public ImageResponse register(UUID houseId, RegisterImageRequest request) {
        House house = load(houseId);

        String expectedPrefix = pathFor(house);
        if (!request.publicId().startsWith(expectedPrefix)) {
            // The whole reason direct upload is safe. See the class comment.
            throw new ForeignAssetException(
                    "Public id '" + request.publicId() + "' is not under " + expectedPrefix);
        }

        HouseImage image = new HouseImage();
        image.house = house;
        image.cloudinaryPublicId = request.publicId();
        image.width = request.width();
        image.height = request.height();
        image.format = request.format();
        image.bytes = request.bytes();
        image.uploadedAt = Instant.now();
        image.position = nextPosition(houseId);
        // First photo becomes the cover by default. A house with images but no cover
        // would show a blank card on the marketplace, which no agent intends.
        image.cover = request.cover() || house.images.isEmpty();

        if (image.cover) {
            clearExistingCover(houseId);
        }

        image.persist();
        house.images.add(image);
        house.updatedAt = Instant.now();
        listings.sync(house);

        return ImageResponse.from(image, urls);
    }

    // ------------------------------------------------------------- organising

    @Transactional
    public List<ImageResponse> list(UUID houseId) {
        load(houseId);
        return HouseImage.<HouseImage>find("house.id", Sort.by("position"), houseId)
                .list()
                .stream()
                .map(image -> ImageResponse.from(image, urls))
                .toList();
    }

    @Transactional
    public ImageResponse makeCover(UUID houseId, UUID imageId) {
        House house = load(houseId);
        HouseImage image = loadImage(houseId, imageId);

        if (!image.cover) {
            clearExistingCover(houseId);
            image.cover = true;
        }

        house.updatedAt = Instant.now();
        listings.sync(house);
        return ImageResponse.from(image, urls);
    }

    /**
     * Removes an image from the gallery and, afterwards, from Cloudinary.
     *
     * <p>The remote asset is destroyed only once this transaction has committed. Doing
     * it inline would mean a rolled-back transaction had already deleted the file, and
     * an HTTP call inside a database transaction holds a connection open for the length
     * of somebody else's network.
     */
    @Transactional
    public void delete(UUID houseId, UUID imageId) {
        House house = load(houseId);
        HouseImage image = loadImage(houseId, imageId);

        boolean wasCover = image.cover;
        String publicId = image.cloudinaryPublicId;

        house.images.remove(image);
        image.delete();
        // Flushed so the partial unique index sees the deletion before a replacement
        // cover is set; otherwise both rows briefly claim to be the cover.
        Panache.flush();

        if (wasCover) {
            // Promote the next photo rather than leaving the house with none. A gallery
            // that silently loses its marketplace thumbnail because someone deleted the
            // first picture is a bug reported as "our listing disappeared".
            HouseImage replacement = HouseImage.<HouseImage>find(
                            "house.id", Sort.by("position"), houseId)
                    .firstResult();
            if (replacement != null) {
                replacement.cover = true;
            }
        }

        house.updatedAt = Instant.now();
        listings.sync(house);

        assets.destroyAfterCommit(publicId);
    }

    // ---------------------------------------------------------------- helpers

    /** {@code houses/{agencyId}/{houseId}/} - the prefix a signature is bound to. */
    private String pathFor(House house) {
        return config.rootFolder() + "/" + house.agencyId + "/" + house.id + "/";
    }

    private int nextPosition(UUID houseId) {
        HouseImage last = HouseImage.<HouseImage>find(
                        "house.id", Sort.by("position").descending(), houseId)
                .firstResult();
        return last == null ? 0 : last.position + 1;
    }

    /**
     * Clears the current cover and flushes.
     *
     * <p>The flush matters: {@code uq_house_image_cover} is a partial unique index, and
     * without it Hibernate is free to insert the new cover before updating the old one,
     * which the database refuses.
     */
    private void clearExistingCover(UUID houseId) {
        HouseImage current = HouseImage.<HouseImage>find("house.id = ?1 and cover = true",
                        houseId)
                .firstResult();
        if (current != null) {
            current.cover = false;
            Panache.flush();
        }
    }

    private House load(UUID houseId) {
        House house = House.findById(houseId);
        if (house == null) {
            throw new HouseNotFoundException(houseId);
        }
        return house;
    }

    /**
     * The house id is part of the lookup rather than checked afterwards, so an image
     * belonging to another house - or, through the tenant filter, another agency - is
     * simply not found.
     */
    private HouseImage loadImage(UUID houseId, UUID imageId) {
        HouseImage image = HouseImage.<HouseImage>find("id = ?1 and house.id = ?2",
                        imageId, houseId)
                .firstResult();
        if (image == null) {
            throw new ImageNotFoundException(imageId);
        }
        return image;
    }
}
