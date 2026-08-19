-- ============================================================================
--  property-service, initial schema
-- ============================================================================
--  Two shapes live here and they answer opposite questions:
--
--    house / house_image   agency-private. Every row carries agency_id and every
--                          query is narrowed to one agency by Hibernate.
--    marketplace_listing   deliberately public and cross-agency. It holds only
--                          houses that are AVAILABLE and published, and only the
--                          columns a stranger may see.
-- ============================================================================

CREATE TABLE house (
    id                UUID           PRIMARY KEY,
    agency_id         VARCHAR(64)    NOT NULL,
    landlord_id       UUID           NOT NULL,
    title             VARCHAR(200)   NOT NULL,
    description       VARCHAR(4000),
    street            VARCHAR(200)   NOT NULL,
    district          VARCHAR(120),
    city              VARCHAR(120)   NOT NULL,
    -- VARCHAR rather than CHAR throughout: PostgreSQL blank-pads CHAR, so a CHAR(3)
    -- currency read back as 'XOF' compares unequal to 'XOF' in some drivers.
    country_code      VARCHAR(2)     NOT NULL,
    latitude          DOUBLE PRECISION,
    longitude         DOUBLE PRECISION,
    bedrooms          INTEGER        NOT NULL,
    bathrooms         INTEGER        NOT NULL,
    size_sqm          INTEGER,
    price_per_month   NUMERIC(14, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    published         BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT house_status_valid
        CHECK (status IN ('AVAILABLE', 'RESERVED', 'OCCUPIED', 'UNAVAILABLE')),
    CONSTRAINT house_price_positive
        CHECK (price_per_month > 0),
    CONSTRAINT house_rooms_non_negative
        CHECK (bedrooms >= 0 AND bathrooms >= 0)
);

-- Every agency-scoped query filters on agency_id first, so it leads every index
-- on this table. An index that omits it is nearly useless once a second agency
-- signs up.
CREATE INDEX idx_house_agency            ON house (agency_id, created_at DESC);
CREATE INDEX idx_house_agency_status     ON house (agency_id, status);
CREATE INDEX idx_house_agency_landlord   ON house (agency_id, landlord_id);


CREATE TABLE house_image (
    id           UUID         PRIMARY KEY,
    agency_id    VARCHAR(64)  NOT NULL,
    house_id     UUID         NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    position     INTEGER      NOT NULL DEFAULT 0,
    is_cover     BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_house_image_house
        FOREIGN KEY (house_id) REFERENCES house (id) ON DELETE CASCADE
);

CREATE INDEX idx_house_image_house ON house_image (house_id, position);

-- At most one cover photo per house. A partial unique index expresses this
-- directly; enforcing it in application code invites two agents editing the same
-- gallery to produce two covers.
CREATE UNIQUE INDEX uq_house_image_cover
    ON house_image (house_id)
    WHERE is_cover;


CREATE TABLE marketplace_listing (
    house_id         UUID           PRIMARY KEY,
    agency_id        VARCHAR(64)    NOT NULL,
    title            VARCHAR(200)   NOT NULL,
    description      VARCHAR(4000),
    district         VARCHAR(120),
    city             VARCHAR(120)   NOT NULL,
    country_code     VARCHAR(2)     NOT NULL,
    bedrooms         INTEGER        NOT NULL,
    bathrooms        INTEGER        NOT NULL,
    size_sqm         INTEGER,
    price_per_month  NUMERIC(14, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    cover_image_key  VARCHAR(512),
    listed_at        TIMESTAMPTZ    NOT NULL,

    CONSTRAINT fk_listing_house
        FOREIGN KEY (house_id) REFERENCES house (id) ON DELETE CASCADE
);

-- Search-shaped indexes. lower(city) is indexed explicitly because the search
-- query compares case-insensitively; without this the predicate cannot use an
-- index at all.
CREATE INDEX idx_listing_city         ON marketplace_listing (lower(city));
CREATE INDEX idx_listing_country_city ON marketplace_listing (country_code, lower(city));
CREATE INDEX idx_listing_price        ON marketplace_listing (price_per_month);
CREATE INDEX idx_listing_bedrooms     ON marketplace_listing (bedrooms);
CREATE INDEX idx_listing_listed_at    ON marketplace_listing (listed_at DESC);

COMMENT ON TABLE marketplace_listing IS
    'Public cross-agency projection of listable houses. Contains no landlord identity '
    'or internal agency data. Maintained by ListingProjector; becomes a Kafka '
    'consumer of lease events in Phase 2.';
