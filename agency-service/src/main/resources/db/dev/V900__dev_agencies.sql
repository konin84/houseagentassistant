-- ============================================================================
--  Development fixtures: the agencies the seeded Keycloak users belong to
-- ============================================================================
--  infra/keycloak seeds agent-a, admin-a and agent-b with agency_id claims of
--  agency-a and agency-b. Without matching rows here those users are members of
--  agencies that do not exist, and the first thing an admin does - open their own
--  profile - answers 404.
--
--  On a real system the order is the other way round: a platform admin registers
--  the agency, then creates its first administrator, so the two can never
--  disagree. These rows exist only because the realm import creates people
--  without going through that door.
--
--  Loaded only under the dev profile - see quarkus.flyway.locations in
--  application-dev.yaml. Production runs db/migration alone, so no fixture ever
--  reaches it.
--
--  V900 rather than V2 to leave the ordinary migration numbers free: real schema
--  changes keep counting up from V2 without ever colliding with this.
-- ============================================================================

INSERT INTO agency (agency_id, name, city, country_code, contact_email, status,
                    created_at, updated_at)
VALUES
    ('agency-a', 'Cocody Immobilier', 'Abidjan', 'CI',
     'contact@cocody-immobilier.ci', 'ACTIVE', now(), now()),
    ('agency-b', 'Plateau Properties', 'Abidjan', 'CI',
     'contact@plateau-properties.ci', 'ACTIVE', now(), now())
ON CONFLICT (agency_id) DO NOTHING;
