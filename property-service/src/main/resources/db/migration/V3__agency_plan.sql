-- ============================================================================
--  This service's copy of what each agency is allowed
-- ============================================================================
--  property-service enforces the house limit, so it has to know the limit. The
--  obvious way is to ask agency-service on every house creation - and that would
--  mean an agency-service outage stopping every agency on the platform from
--  listing anything, which is a poor trade for a number that changes about once
--  a year per customer.
--
--  So the plan is published and kept here instead. Same reasoning as
--  lease_billing in payment-service: a replica of somebody else's fact, built
--  from events, never written through this service's API.
--
--  No agency_id discriminator. The consumer writes rows for every agency, and a
--  tenant filter would leave it able to write only for whichever agency happened
--  to be current - which on a Kafka thread is none of them.
-- ============================================================================

CREATE TABLE agency_plan (
    agency_id   VARCHAR(64) PRIMARY KEY,
    plan        VARCHAR(32) NOT NULL,

    -- Null means unlimited, and is stored that way rather than as a very large
    -- number so that "no ceiling" can never be mistaken for a ceiling somebody
    -- chose. Every comparison against it has to handle the null explicitly,
    -- which is the point.
    max_houses  INTEGER,

    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT agency_plan_max_houses_sane
        CHECK (max_houses IS NULL OR max_houses > 0)
);
