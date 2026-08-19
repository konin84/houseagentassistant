package com.digitalpartner.houseagent.property.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * A photo of a house.
 * <p>
 * Only the storage key lives in Postgres; the bytes live in S3/MinIO and are uploaded
 * by the browser straight to object storage using a presigned URL. Streaming image
 * uploads through the service would make it slow, memory-hungry and stateful for no
 * benefit.
 */
@Entity
@Table(name = "house_image")
public class HouseImage extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    /**
     * Repeated from the parent house rather than joined.
     * <p>
     * Hibernate's tenant filter is applied per entity, not transitively through
     * associations, so a child entity without its own discriminator would be readable
     * across agencies by anyone who guessed an id.
     */
    @TenantId
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    public House house;

    /**
     * The Cloudinary public id, e.g. {@code houses/{agencyId}/{houseId}/{uuid}}.
     *
     * <p>Never a URL. A URL bakes in the cloud name, the transformation and the delivery
     * host, all of which are rendering decisions that belong at read time - see
     * {@code CloudinaryUrls}. This is the durable handle, and the only one by which the
     * asset can later be transformed or destroyed.
     *
     * <p>The path is agency- and house-scoped, which is what a signature is bound to and
     * what registration checks.
     */
    @Column(name = "cloudinary_public_id", nullable = false, length = 512)
    public String cloudinaryPublicId;

    /** Display order within the gallery. */
    @Column(name = "position", nullable = false)
    public int position;

    /** The one image used as the marketplace thumbnail. */
    @Column(name = "is_cover", nullable = false)
    public boolean cover;

    /**
     * Dimensions and size as Cloudinary reported them at upload.
     *
     * <p>Stored so a client can reserve the right space before the bytes arrive, which
     * is the difference between a gallery that loads and one that jumps around as it
     * does. All nullable: a caller that did not echo the upload response back still has
     * a perfectly usable image.
     */
    @Column(name = "width")
    public Integer width;

    @Column(name = "height")
    public Integer height;

    @Column(name = "format", length = 16)
    public String format;

    @Column(name = "bytes")
    public Long bytes;

    @Column(name = "uploaded_at", nullable = false)
    public java.time.Instant uploadedAt = java.time.Instant.now();
}
