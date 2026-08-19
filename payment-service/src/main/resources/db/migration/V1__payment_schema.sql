-- ============================================================================
--  payment-service, initial schema
-- ============================================================================
--  Four groups of tables:
--
--    lease_billing              this service's own replica of the lease terms it
--                               bills against. Built from lease events, never by a
--                               call to lease-service.
--    invoice / payment / payout agency-scoped money. Every one carries agency_id and
--                               is filtered by Hibernate's @TenantId.
--    *_view                     projections for renters and landlords, who are
--                               platform-wide and therefore cannot be tenant-filtered.
--    outbox_event               payment events awaiting relay to Kafka.
-- ============================================================================


-- ============================================================================
--  Settlement policy
-- ============================================================================
--  Whether the platform holds money or merely records it is an agency-level
--  decision, not a platform-wide one: an agency that collects rent on a
--  landlord's behalf needs commission and payouts, while one that only brokers
--  the introduction needs neither. Both must be able to run side by side.
--
--  Deliberately not @TenantId: the invoice and payout jobs run on a scheduler
--  with no JWT and must read every agency's policy to know what to do.
-- ============================================================================
CREATE TABLE agency_settlement_config (
    agency_id       VARCHAR(64)  PRIMARY KEY,
    mode            VARCHAR(32)  NOT NULL,
    -- Basis points, so 750 = 7.5%. Integer arithmetic on money beats a percentage
    -- stored as a float, which cannot represent 7.1% exactly.
    commission_bps  INTEGER      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT settlement_mode_valid
        CHECK (mode IN ('PLATFORM_COLLECTS', 'DIRECT_TO_LANDLORD')),
    -- A commission over 100% is always a data-entry error, never a business model.
    CONSTRAINT commission_within_range
        CHECK (commission_bps BETWEEN 0 AND 10000),
    -- Commission is meaningless if the platform never touches the money. Rejecting
    -- the combination outright stops a payout being computed from a rate nobody
    -- intended to apply.
    CONSTRAINT commission_only_when_collecting
        CHECK (mode = 'PLATFORM_COLLECTS' OR commission_bps = 0)
);


-- ============================================================================
--  Lease billing replica
-- ============================================================================
--  payment-service bills against terms owned by lease-service. It keeps its own
--  copy rather than querying across a service boundary: an invoice run must not
--  fail because another service is down, and the terms that were in force when an
--  invoice was raised are a historical fact that must not change retroactively.
--
--  No @TenantId. The generator sweeps every agency's leases on a schedule and
--  establishes each agency explicitly before writing.
-- ============================================================================
CREATE TABLE lease_billing (
    lease_id          UUID           PRIMARY KEY,
    agency_id         VARCHAR(64)    NOT NULL,
    house_id          UUID           NOT NULL,
    house_reference   VARCHAR(200),
    renter_id         UUID           NOT NULL,
    renter_name       VARCHAR(200),
    landlord_id       UUID           NOT NULL,
    rent_amount       NUMERIC(14, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    cadence           VARCHAR(20)    NOT NULL,
    due_day_of_month  INTEGER        NOT NULL,
    start_date        DATE           NOT NULL,
    end_date          DATE,
    -- Only ACTIVE leases are invoiced. Ended ones are kept so their history stays
    -- readable rather than being deleted out from under old invoices.
    billing_status    VARCHAR(20)    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,

    CONSTRAINT lease_billing_status_valid
        CHECK (billing_status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT lease_billing_cadence_valid
        CHECK (cadence IN ('MONTHLY', 'QUARTERLY', 'BIANNUAL', 'ANNUAL')),
    CONSTRAINT lease_billing_due_day_valid
        CHECK (due_day_of_month BETWEEN 1 AND 28)
);

-- The generator asks for exactly this: every lease still being billed.
CREATE INDEX idx_lease_billing_active
    ON lease_billing (billing_status)
    WHERE billing_status = 'ACTIVE';


-- ============================================================================
--  Invoices
-- ============================================================================
CREATE TABLE invoice (
    id                  UUID           PRIMARY KEY,
    agency_id           VARCHAR(64)    NOT NULL,
    lease_id            UUID           NOT NULL,
    renter_id           UUID           NOT NULL,
    landlord_id         UUID           NOT NULL,
    house_reference     VARCHAR(200),
    period_start        DATE           NOT NULL,
    period_end          DATE           NOT NULL,
    due_date            DATE           NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    amount_paid         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    -- Set when RentOverdue has been emitted, so the arrears sweep announces a late
    -- invoice once rather than every time it runs.
    overdue_notified_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT invoice_status_valid
        CHECK (status IN ('DUE', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')),
    CONSTRAINT invoice_amount_positive
        CHECK (amount > 0),
    -- Paying more than the invoice is never a rounding artefact; it means two
    -- payments were recorded for the same money.
    CONSTRAINT invoice_not_overpaid
        CHECK (amount_paid >= 0 AND amount_paid <= amount),
    CONSTRAINT invoice_period_ordered
        CHECK (period_end > period_start)
);

-- ============================================================================
--  What makes invoice generation safe to repeat
-- ============================================================================
--  The generator runs on a timer and re-derives the whole schedule of every active
--  lease each time. Without this constraint a restart, an overlapping run or a
--  redelivered LeaseSigned event would bill a renter twice for the same month.
--  With it, the second insert simply fails and is skipped.
-- ============================================================================
CREATE UNIQUE INDEX uq_invoice_lease_period ON invoice (lease_id, period_start);

CREATE INDEX idx_invoice_agency_status ON invoice (agency_id, status);
-- Drives the arrears sweep: unpaid invoices, oldest due date first.
CREATE INDEX idx_invoice_unpaid_due
    ON invoice (due_date)
    WHERE status IN ('DUE', 'PARTIALLY_PAID');


-- ============================================================================
--  Payments
-- ============================================================================
CREATE TABLE payment (
    id                 UUID           PRIMARY KEY,
    agency_id          VARCHAR(64)    NOT NULL,
    invoice_id         UUID           NOT NULL,
    lease_id           UUID           NOT NULL,
    renter_id          UUID           NOT NULL,
    landlord_id        UUID           NOT NULL,
    amount             NUMERIC(14, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    method             VARCHAR(20)    NOT NULL,
    -- The provider's own id for this transaction. Null for cash taken at the office,
    -- where there is no provider to be idempotent against.
    provider_reference VARCHAR(200),
    status             VARCHAR(20)    NOT NULL,
    failure_reason     VARCHAR(200),
    initiated_at       TIMESTAMPTZ    NOT NULL,
    settled_at         TIMESTAMPTZ,
    version            BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT payment_status_valid
        CHECK (status IN ('PENDING', 'SETTLED', 'FAILED')),
    CONSTRAINT payment_method_valid
        CHECK (method IN ('MOBILE_MONEY', 'CASH', 'BANK_TRANSFER', 'CARD')),
    CONSTRAINT payment_amount_positive
        CHECK (amount > 0),
    CONSTRAINT payment_settled_has_timestamp
        CHECK (status <> 'SETTLED' OR settled_at IS NOT NULL),
    CONSTRAINT fk_payment_invoice
        FOREIGN KEY (invoice_id) REFERENCES invoice (id)
);

-- ============================================================================
--  Webhook idempotency
-- ============================================================================
--  Payment providers retry their callbacks, and a retry that arrives after the
--  first was processed must not take the renter's money twice. The provider's own
--  reference is the natural idempotency key, so the database refuses the duplicate
--  and the application maps it to "already recorded" rather than a new payment.
--
--  Partial, because cash has no reference and several cash payments legitimately
--  carry NULL.
-- ============================================================================
CREATE UNIQUE INDEX uq_payment_provider_reference
    ON payment (provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE INDEX idx_payment_invoice  ON payment (invoice_id);
CREATE INDEX idx_payment_agency   ON payment (agency_id, settled_at DESC);


-- ============================================================================
--  Payouts
-- ============================================================================
--  Only written when the agency's mode is PLATFORM_COLLECTS. Under
--  DIRECT_TO_LANDLORD the renter pays the landlord and the platform never holds
--  the money, so there is nothing to remit and no row here at all.
-- ============================================================================
CREATE TABLE payout (
    id                 UUID           PRIMARY KEY,
    agency_id          VARCHAR(64)    NOT NULL,
    landlord_id        UUID           NOT NULL,
    payment_id         UUID           NOT NULL UNIQUE,
    lease_id           UUID           NOT NULL,
    gross_amount       NUMERIC(14, 2) NOT NULL,
    commission_amount  NUMERIC(14, 2) NOT NULL,
    net_amount         NUMERIC(14, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    settled_at         TIMESTAMPTZ,

    CONSTRAINT payout_status_valid
        CHECK (status IN ('PENDING', 'SETTLED')),
    -- The three amounts must agree. A payout that does not reconcile is worse than
    -- one that fails to insert.
    CONSTRAINT payout_amounts_reconcile
        CHECK (net_amount = gross_amount - commission_amount
               AND commission_amount >= 0
               AND net_amount >= 0),
    CONSTRAINT fk_payout_payment
        FOREIGN KEY (payment_id) REFERENCES payment (id)
);

CREATE INDEX idx_payout_agency_status ON payout (agency_id, status);


-- ============================================================================
--  Renter's own ledger
-- ============================================================================
--  The third isolation rule. A renter is a platform-wide principal who may rent
--  from agency A this year and agency B next, so their token carries no agency_id
--  and the tenant filter cannot serve them. This table therefore has no
--  discriminator, and isolation is an explicit renter_id predicate taken from the
--  validated token - never from a request parameter.
-- ============================================================================
CREATE TABLE renter_invoice_view (
    invoice_id       UUID           PRIMARY KEY,
    renter_id        UUID           NOT NULL,
    agency_id        VARCHAR(64)    NOT NULL,
    lease_id         UUID           NOT NULL,
    house_reference  VARCHAR(200),
    period_start     DATE           NOT NULL,
    period_end       DATE           NOT NULL,
    due_date         DATE           NOT NULL,
    amount           NUMERIC(14, 2) NOT NULL,
    amount_paid      NUMERIC(14, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    status           VARCHAR(20)    NOT NULL,

    CONSTRAINT fk_renter_view_invoice
        FOREIGN KEY (invoice_id) REFERENCES invoice (id) ON DELETE CASCADE
);

CREATE INDEX idx_renter_view_renter ON renter_invoice_view (renter_id, due_date DESC);


-- ============================================================================
--  Landlord's earnings
-- ============================================================================
--  Same reasoning as above, for the other platform-wide principal. Shows what has
--  actually been received against their houses, net of the agency's commission, so
--  a landlord can reconcile what they were told with what they were paid.
-- ============================================================================
CREATE TABLE landlord_earning_view (
    payment_id       UUID           PRIMARY KEY,
    landlord_id      UUID           NOT NULL,
    agency_id        VARCHAR(64)    NOT NULL,
    lease_id         UUID           NOT NULL,
    house_reference  VARCHAR(200),
    renter_name      VARCHAR(200),
    gross_amount     NUMERIC(14, 2) NOT NULL,
    commission_amount NUMERIC(14, 2) NOT NULL,
    net_amount       NUMERIC(14, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    settled_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT fk_landlord_earning_payment
        FOREIGN KEY (payment_id) REFERENCES payment (id) ON DELETE CASCADE
);

CREATE INDEX idx_landlord_earning_landlord
    ON landlord_earning_view (landlord_id, settled_at DESC);


-- ============================================================================
--  Transactional outbox
-- ============================================================================
--  Same reasoning as lease-service: a payment that settled but whose event was
--  lost would leave the landlord permanently un-notified, and no amount of
--  retrying the email fixes an event that was never published.
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

CREATE INDEX idx_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
