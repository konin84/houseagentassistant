-- ============================================================================
--  agency-service, initial schema
-- ============================================================================
--  One table. The people are in Keycloak, which is the store of record for
--  accounts, roles and credentials - duplicating them here would create two
--  answers to "who is an agent of this agency" that can disagree.
--
--  What Keycloak cannot hold is the agency itself. Until now an agency was only
--  a string in a token: no name, no address, nothing to show a landlord about
--  who manages their house. That is what this table is for.
-- ============================================================================

CREATE TABLE agency (
    -- The same value that appears as the agency_id claim, and as the tenant
    -- discriminator in every other service. A slug rather than a UUID because it
    -- is read by humans in tokens and logs, and because it is chosen once when
    -- the agency is created and never again.
    agency_id      VARCHAR(64)  PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    city           VARCHAR(120),
    country_code   VARCHAR(2),
    contact_email  VARCHAR(320),
    contact_phone  VARCHAR(40),
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT agency_status_valid
        CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    -- Lower-case, no spaces. This value ends up in a JWT claim and in every
    -- tenant-filtered query on the platform; letting it vary by case would mean
    -- 'Agency-A' and 'agency-a' quietly becoming two different customers.
    CONSTRAINT agency_id_is_a_slug
        CHECK (agency_id ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$')
);

-- Deliberately no `staff` table.
--
-- Keycloak already knows which users carry which agency_id, and a local roster
-- would be a second copy that drifts the first time somebody is disabled there
-- and not here. Listing staff is a query against Keycloak, not a join.
