-- ============================================================================
--  notification-service, initial schema
-- ============================================================================
--  The only service on the platform with no agency discriminator anywhere.
--
--  Everything here is keyed on a platform-wide party - a landlord or a renter -
--  who may deal with several agencies at once and has exactly one email address
--  regardless. Filtering contacts by agency would mean the same person being
--  emailed twice, or their address being editable by one agency and not another.
--  The agency is stored on a delivery record as data, for support and debugging,
--  and is never used to narrow a query.
-- ============================================================================


-- ============================================================================
--  Contact details
-- ============================================================================
--  Deliberately not carried inside the events themselves. An address is contact
--  data that changes independently of any payment, and a Kafka topic is retained
--  far longer than someone's consent to be emailed at a particular address.
--  Events carry party ids; this table turns one into a recipient.
--
--  Keycloak will eventually own this. Until it does, an agency onboards its
--  landlords here and a landlord can correct their own row.
-- ============================================================================
CREATE TABLE contact (
    party_id           UUID         PRIMARY KEY,
    email              VARCHAR(320) NOT NULL,
    display_name       VARCHAR(200),
    -- BCP-47. Rent notices in the wrong language get ignored, not translated.
    locale             VARCHAR(16)  NOT NULL DEFAULT 'fr',
    notify_on_payment  BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_on_arrears  BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at         TIMESTAMPTZ  NOT NULL,

    -- 320 is the maximum length of an addr-spec. The check is a sanity floor, not
    -- an attempt to validate email addresses with a pattern, which never ends well.
    CONSTRAINT contact_email_plausible
        CHECK (position('@' in email) > 1)
);


-- ============================================================================
--  Delivery log
-- ============================================================================
--  Every message this service decided to send, whether or not it got through.
--  Two jobs:
--
--    1. Idempotency. Payment events are delivered at least once, so the same
--       PaymentSettled will arrive again. (event_id, party_id) is unique, so the
--       second attempt to log it fails and no second email is sent.
--
--    2. Retry. An SMTP timeout is a transient failure of the delivery, not of the
--       payment. The row survives it, and the sweep tries again.
-- ============================================================================
CREATE TABLE notification (
    id            UUID          PRIMARY KEY,
    -- The envelope id of the event that caused this. Half of the idempotency key.
    event_id      UUID          NOT NULL,
    event_type    VARCHAR(100)  NOT NULL,
    agency_id     VARCHAR(64),
    -- Who it is for, as a platform party. Resolved to an address at send time.
    party_id      UUID          NOT NULL,
    recipient     VARCHAR(320)  NOT NULL,
    subject       VARCHAR(300)  NOT NULL,
    body          TEXT          NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    attempts      INTEGER       NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL,
    sent_at       TIMESTAMPTZ,

    CONSTRAINT notification_status_valid
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'ABANDONED')),
    CONSTRAINT notification_sent_has_timestamp
        CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
);

-- ============================================================================
--  One message per person per event
-- ============================================================================
--  Keyed on the pair rather than on event_id alone, because one event legitimately
--  reaches more than one person: rent going unpaid concerns the renter who owes it
--  and the landlord whose money is late. Uniqueness on event_id alone would let the
--  first recipient silently suppress the second.
-- ============================================================================
CREATE UNIQUE INDEX uq_notification_event_party ON notification (event_id, party_id);

-- The retry sweep asks for exactly this: everything not yet delivered, oldest
-- first, so a backlog drains in the order it built up.
CREATE INDEX idx_notification_undelivered
    ON notification (created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_notification_party ON notification (party_id, created_at DESC);
