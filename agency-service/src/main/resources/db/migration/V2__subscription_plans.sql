-- ============================================================================
--  Subscription plans
-- ============================================================================
--  What an agency is entitled to. The limit is on houses in the catalogue rather
--  than houses advertised: an agency on the free plan may hold five properties
--  whether or not any are currently on the marketplace.
--
--  The ceiling itself is not stored here. It belongs to the plan, and putting a
--  copy on every agency row would mean raising the free tier from five to ten
--  became an UPDATE across every customer instead of a one-line change. What is
--  stored is which plan they are on.
-- ============================================================================

ALTER TABLE agency
    ADD COLUMN plan            VARCHAR(32),
    ADD COLUMN plan_changed_at TIMESTAMPTZ;

-- Every agency that already exists predates plans, and the free tier is the
-- honest description of what they were getting.
UPDATE agency SET plan = 'FREE', plan_changed_at = now() WHERE plan IS NULL;

ALTER TABLE agency
    ALTER COLUMN plan SET NOT NULL,
    ALTER COLUMN plan_changed_at SET NOT NULL;

ALTER TABLE agency
    ADD CONSTRAINT agency_plan_valid
        CHECK (plan IN ('FREE', 'STARTER', 'PROFESSIONAL', 'ENTERPRISE'));


-- ============================================================================
--  Transactional outbox
-- ============================================================================
--  property-service enforces the limit, so it has to be told what the limit is.
--  Asking agency-service on every house creation would make an agency-service
--  outage stop every agency on the platform from listing anything - so the plan
--  is published instead, and property-service keeps its own copy.
--
--  Same reasoning as everywhere else: a plan change committed but never published
--  would leave an agency paying for a tier they cannot use, and no amount of
--  retrying fixes an event that was never written.
-- ============================================================================
CREATE TABLE outbox_event (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    agency_id       VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
