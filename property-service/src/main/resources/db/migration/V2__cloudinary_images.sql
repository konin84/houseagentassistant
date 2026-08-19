-- ============================================================================
--  House images move to Cloudinary
-- ============================================================================
--  V1 called the column storage_key, which was deliberately vague while the
--  host was undecided. It is Cloudinary now, and the value stored is a
--  Cloudinary public id - a path like houses/{agency}/{house}/{uuid} - not an
--  S3 object key and not a URL.
--
--  Naming it for what it is matters more than it sounds: a public id is the
--  only handle by which an asset can later be transformed or destroyed, and
--  code that thinks it holds a URL will happily store one and break both.
-- ============================================================================

ALTER TABLE house_image RENAME COLUMN storage_key TO cloudinary_public_id;

-- ============================================================================
--  Delivery metadata
-- ============================================================================
--  Returned by Cloudinary when the browser finishes uploading, and stored so a
--  client can size a layout before fetching the bytes. All nullable: an image
--  registered by an older client, or by a caller that did not echo the upload
--  response back, is still a perfectly usable image.
-- ============================================================================
ALTER TABLE house_image
    ADD COLUMN width       INTEGER,
    ADD COLUMN height      INTEGER,
    ADD COLUMN format      VARCHAR(16),
    ADD COLUMN bytes       BIGINT,
    ADD COLUMN uploaded_at TIMESTAMPTZ;

-- Existing rows predate the column and have no honest value to put in it.
UPDATE house_image SET uploaded_at = now() WHERE uploaded_at IS NULL;

ALTER TABLE house_image ALTER COLUMN uploaded_at SET NOT NULL;

-- The public id is the identity of the asset, so two rows must never claim the
-- same one. Without this, registering an id that another house already holds
-- would let one agency attach another agency's photograph to its own listing.
CREATE UNIQUE INDEX uq_house_image_public_id ON house_image (cloudinary_public_id);


-- The projection mirrors the rename, for the same reason.
ALTER TABLE marketplace_listing RENAME COLUMN cover_image_key TO cover_image_public_id;
