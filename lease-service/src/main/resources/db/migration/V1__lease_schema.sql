-- ============================================================================
--  lease-service, initial schema
-- ============================================================================

-- Required for the exclusion constraint below: it lets a GiST index mix an
-- equality column (house_id) with a range column (the lease period). Without
-- btree_gist, GiST cannot index UUID equality and the constraint will not build.
CREATE EXTENSION IF NOT EXISTS btree_gist;


CREATE TABLE lease (
    id                 UUID           PRIMARY KEY,
    agency_id          VARCHAR(64)    NOT NULL,
    house_id           UUID           NOT NULL,
    renter_id          UUID           NOT NULL,
    landlord_id        UUID           NOT NULL,
    renter_name        VARCHAR(200)   NOT NULL,
    renter_phone       VARCHAR(40),
    house_reference    VARCHAR(200),
    rent_amount        NUMERIC(14, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    cadence            VARCHAR(20)    NOT NULL,
    due_day_of_month   INTEGER        NOT NULL,
    deposit_amount     NUMERIC(14, 2),
    start_date         DATE           NOT NULL,
    end_date           DATE,
    status             VARCHAR(20)    NOT NULL,
    end_reason         VARCHAR(200),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT lease_status_valid
        CHECK (status IN ('PENDING_MOVE_IN', 'ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT lease_cadence_valid
        CHECK (cadence IN ('MONTHLY', 'QUARTERLY', 'BIANNUAL', 'ANNUAL')),
    CONSTRAINT lease_rent_positive
        CHECK (rent_amount > 0),
    -- 1-28 only. A lease due on the 31st has no due date in February, and every
    -- scheme for papering over that afterwards is worse than forbidding it.
    CONSTRAINT lease_due_day_valid
        CHECK (due_day_of_month BETWEEN 1 AND 28),
    CONSTRAINT lease_dates_ordered
        CHECK (end_date IS NULL OR end_date > start_date)
);


-- ============================================================================
--  The constraint that makes double-letting impossible
-- ============================================================================
--  Two agents signing the same house at the same instant is a race no amount of
--  "check then insert" application code can win: both transactions read "no
--  active lease" before either writes. Distributed locks or a saga would work
--  but add moving parts that can themselves fail.
--
--  PostgreSQL can simply refuse. This says: no two rows may share a house_id
--  while their date ranges overlap, considering only leases that actually
--  occupy the house. One transaction commits; the other raises a violation that
--  the application maps to 409 Conflict.
--
--  '[)' makes the range half-open, so a lease ending 1 June and another
--  starting 1 June do not count as overlapping - back-to-back tenancies are
--  legal, simultaneous ones are not. A NULL end_date yields an unbounded range,
--  which is exactly right for an open-ended lease.
--
--  The WHERE clause must list precisely the states LeaseStatus.occupiesHouse()
--  returns true for. If one changes without the other, double-letting silently
--  becomes possible again.
-- ============================================================================
ALTER TABLE lease ADD CONSTRAINT no_overlapping_active_lease
    EXCLUDE USING gist (
        house_id WITH =,
        daterange(start_date, end_date, '[)') WITH &&
    )
    WHERE (status IN ('PENDING_MOVE_IN', 'ACTIVE'));

-- Deliberately NOT scoped by agency_id. Two different agencies letting the same
-- physical house at the same time is the worst version of this bug, not an
-- exception to it.

CREATE INDEX idx_lease_agency_status   ON lease (agency_id, status);
CREATE INDEX idx_lease_agency_house    ON lease (agency_id, house_id);
CREATE INDEX idx_lease_renter          ON lease (renter_id, start_date DESC);


-- ============================================================================
--  Landlord portfolio projection
-- ============================================================================
--  Landlords are platform-wide principals whose tokens carry no agency_id, so
--  they cannot be served by the tenant filter. This table has no agency
--  discriminator; isolation is an explicit landlord_id predicate taken from the
--  validated token. It holds only what a landlord is entitled to see.
-- ============================================================================
CREATE TABLE landlord_lease_view (
    lease_id          UUID           PRIMARY KEY,
    landlord_id       UUID           NOT NULL,
    agency_id         VARCHAR(64)    NOT NULL,
    house_id          UUID           NOT NULL,
    house_reference   VARCHAR(200),
    renter_id         UUID           NOT NULL,
    renter_name       VARCHAR(200)   NOT NULL,
    renter_phone      VARCHAR(40),
    rent_amount       NUMERIC(14, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    cadence           VARCHAR(20)    NOT NULL,
    due_day_of_month  INTEGER        NOT NULL,
    start_date        DATE           NOT NULL,
    end_date          DATE,
    status            VARCHAR(20)    NOT NULL,

    CONSTRAINT fk_landlord_view_lease
        FOREIGN KEY (lease_id) REFERENCES lease (id) ON DELETE CASCADE
);

-- Every landlord query filters on landlord_id first, so it leads the index.
CREATE INDEX idx_landlord_view_landlord ON landlord_lease_view (landlord_id, status);
CREATE INDEX idx_landlord_view_house    ON landlord_lease_view (house_id);


-- ============================================================================
--  Transactional outbox
-- ============================================================================
--  Events are written here in the same transaction as the lease change, then
--  relayed to Kafka separately. This is what stops a crash between "lease
--  committed" and "event published" from leaving a let house advertised forever.
-- ============================================================================
CREATE TABLE outbox_event (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    agency_id       VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

-- Partial index: the relay only ever asks for unpublished rows, and this keeps
-- that query fast even after millions of events have been relayed and retained.
CREATE INDEX idx_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
