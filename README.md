# houseagentassistant

Multi-agency house rental platform, built as Quarkus microservices.

Rental agencies manage houses on behalf of landlords, advertise the available ones on
a public marketplace, sign leases with renters, collect rent, and notify landlords
when payments settle.

---

## Vocabulary

The words "tenant" and "multi-tenant" collide badly in this domain, so this codebase
never uses them for people. The mapping is:

| Concept | Name in code |
|---|---|
| Isolated SaaS customer - a rental agency | **Agency** (`agency_id`) |
| Person occupying a house and paying rent | **Renter** (`renter_id`) |
| Person owning the house | **Landlord** (`landlord_id`) |
| Agency staff member who lists houses | **Agent** (a role) |
| Contract binding renter, house and terms | **Lease** |

`agency_id` is the multi-tenancy discriminator. `renter_id` is a domain foreign key.
They are never interchangeable.

---

## Five isolation rules

Most of the design follows from the fact that this system has to satisfy five
*different* visibility rules over the same houses and the money they earn:

| Read path | Scoped by | Enforced by |
|---|---|---|
| Agent dashboard | `agency_id` from JWT | Hibernate `@TenantId` filter |
| Public marketplace | nothing - cross-agency on purpose | `marketplace_listing` projection, which has no `@TenantId` |
| Landlord's own portfolio | `party_id` from JWT | `landlord_lease_view` projection plus an explicit `WHERE landlord_id = :party_id` |
| Renter's own payment history | `party_id` from JWT | `renter_invoice_view` projection plus an explicit `WHERE renter_id = :party_id`, never a request parameter |
| Landlord's own earnings | `party_id` from JWT | `landlord_earning_view` projection plus an explicit `WHERE landlord_id = :party_id` |

Only the first of those has a safety net. The rest run against tables with no
discriminator, so a query that forgets its predicate returns everyone's data and
nothing fails - which is why each has a test whose whole job is to notice.

The renter's rule is the sharpest: an invoice a renter can *see* is an invoice a renter
can *pay*, so a missing predicate there would not merely leak data, it would let anyone
spend their own money settling a stranger's rent.

Renters and landlords are **platform-wide** principals, not agency-scoped - a renter
may rent from agency A this year and agency B next. That is why their tokens carry no
`agency_id`, and why `AgencyTenantResolver` fails closed with a sentinel that matches
no agency rather than falling back to a default.

---

## Services

| Service | Port | Status | Owns |
|---|---|---|---|
| `property-service` | 8081 | **Complete** | Houses, images, marketplace listings |
| `lease-service` | 8082 | **Complete** | Leases, payment modality, availability transitions |
| `payment-service` | 8083 | **Complete** | Invoices, payments, commission, landlord payouts |
| `notification-service` | 8084 | **Complete** | Contact details, email delivery and retry |
| `agency-service` | 8085 | **Complete** | Agency signup, subscription plans, staff, and onboarding landlords and renters |

All five validate bearer JWTs from the Keycloak realm in `infra/keycloak/`.
`agency-service` is also the one that *creates* the accounts, through Keycloak's
admin API - see [who creates whom](docs/user-guide.md#who-creates-whom).

Everybody signs in with **a phone number or an email address**, whichever they find
easier - the number is their Keycloak username, so both reach the same account with no
custom identity provider to maintain. It is there for landlords, who know their number
by heart and may check their email monthly.

Two endpoints on the whole platform take no token: the public marketplace, and
`POST /api/signup`. An agency starts itself there, on the free plan, because adding
staff requires being an agency admin and being an agency admin requires an agency - so
the first of each has to come into existence in the same request.

Landlords deliberately have no service of their own: a landlord is a Keycloak
principal, their link to houses lives in `property-service`, what they are owed lives
in `payment-service`, and where to reach them lives in `notification-service`.

`notification-service` is the one service with **no multi-tenancy at all**. Its subjects
are landlords and renters, who are platform-wide: one landlord working with three
agencies is one person with one address who should get one email about one payment. An
agency discriminator on `contact` would mean three copies of that address, corrected in
one place and stale in the other two.

---

## Running it

### Prerequisites

Java 21+ and a PostgreSQL you can reach. Currently configured against the local
`postgresql-x64-18` Windows service on port 5432.

```bash
cp .env.example .env                       # then fill in DB_PASSWORD
cp .env property-service/.env              # Quarkus reads .env per module
cp .env lease-service/.env
cp .env payment-service/.env
cp .env notification-service/.env
cp .env agency-service/.env
```

Create the databases. Each service owns its own, which is what makes them
independently deployable; the test databases are wiped and re-migrated on every run:

```sql
CREATE DATABASE houseagent_property;
CREATE DATABASE houseagent_property_test;
CREATE DATABASE houseagent_lease;
CREATE DATABASE houseagent_lease_test;
CREATE DATABASE houseagent_payment;
CREATE DATABASE houseagent_payment_test;
CREATE DATABASE houseagent_notification;
CREATE DATABASE houseagent_notification_test;
CREATE DATABASE houseagent_agency;
CREATE DATABASE houseagent_agency_test;
```

The lease schema needs the `btree_gist` extension for its exclusion constraint. The
migration creates it, which requires the connecting user to be a superuser or to have
the extension already installed.

Or use the containers instead - see `docker-compose.yml`, and note the port clash
warning at the top of it.

### Configuration

Each service has three YAML files, and which one you open tells you what you are
looking at:

| File | Holds |
|---|---|
| `application.yaml` | What is true everywhere - schema strategy, topic names, serializers - plus the `"%test"` block |
| `application-dev.yaml` | A laptop: localhost database, local Keycloak, SQL logging, Mailpit |
| `application-prod.yaml` | A deployment: everything from the environment, TLS required |

Test overrides live in the shared file rather than an `application-test.yaml`, because
tests are not a deployment target - they are a variation on the shared configuration,
and keeping the two side by side makes the difference visible.

The important distinction between dev and prod is not which values they hold but
whether they have fallbacks. Dev is full of `${DB_PASSWORD:postgres}`; prod is full of
bare `${DB_PASSWORD}`. A missing secret in production should stop the boot naming the
property it wanted, not start a service that quietly connects as `postgres/postgres`:

```bash
java -Dquarkus.profile=prod -jar payment-service/target/quarkus-app/quarkus-run.jar
# 'quarkus.oidc.auth-server-url' property must be configured
```

### Build and test

```bash
./mvnw clean install
```

That also produces `target/quarkus-app/quarkus-run.jar` per service, which is what you
would actually deploy.

### Trying the API

`postman/houseagentassistant.postman_collection.json` covers all 59 endpoints. Import it
with one of the two environments - the collection is identical either way, only the base
URLs differ:

| Environment | Talks to |
|---|---|
| `local-dev.postman_environment.json` | the five service ports directly |
| `gateway.postman_environment.json` | `localhost:8000`, through the gateway |

Run one request from the **Authentication** folder to get a token, then work down a
service folder - each request saves the ids the next one needs, so nothing has to be
copied by hand.

The collection is generated from `postman/build_collection.py`, and
`postman/check_routes.py` compares every request in it against the `@Path` annotations
in the code - a collection that drifts is documentation that lies.

**New here?** `docs/user-guide.md` walks the whole system as a story - an agency lists
a house, a renter finds it, a lease is signed, rent is invoiced and paid, the landlord
is emailed - with the real responses at each step. It doubles as a manual smoke test.

**Building a frontend?** `docs/frontend.md` is written for that: which client to log in
with, what the two claims mean, what each status code implies for the UI, and the
handful of behaviours that surprise people - a house vanishing from the marketplace
when it is let, invoices appearing on their own, and paying being two steps.

### Dev mode

```bash
docker compose up -d keycloak gateway
cd property-service && ../mvnw quarkus:dev
```

Running more than one service at once needs distinct debugger ports - Quarkus dev mode
binds 5005 by default, and the second service to start will fail with `transport error
202: bind failed`:

```bash
cd lease-service && ../mvnw quarkus:dev -Ddebug=5006
```

| | |
|---|---|
| Services | 8081 property, 8082 lease, 8083 payment, 8084 notification, 8085 agency |
| Gateway | http://localhost:8000, dashboard on http://localhost:8001 |
| Keycloak | http://localhost:8180 (`admin` / `admin`) |
| Swagger UI | http://localhost:8081/q/swagger-ui |
| Mailpit | http://localhost:8025 |

---

## API

### Agency-facing - requires `AGENT` or `AGENCY_ADMIN`

```
POST   /api/agency/houses                    create a house (starts unpublished)
GET    /api/agency/houses                    list own agency's houses, paged
GET    /api/agency/houses/{id}               404 if it belongs to another agency
PATCH  /api/agency/houses/{id}               partial update
POST   /api/agency/houses/{id}/publication   advertise on the marketplace
DELETE /api/agency/houses/{id}/publication   withdraw from the marketplace
DELETE /api/agency/houses/{id}               AGENCY_ADMIN only
```

No endpoint takes an `agencyId` parameter. The agency comes from the token, so there
is nothing for a caller to tamper with.

### Public marketplace - anonymous

```
GET /api/marketplace/listings?city=&country=&minPrice=&maxPrice=&minBedrooms=&page=&size=
GET /api/marketplace/listings/{houseId}
```

Page size is capped at 100.

### House images - requires `AGENT` or `AGENCY_ADMIN`

```
POST   /api/agency/houses/{id}/images/upload-ticket   sign one upload
POST   /api/agency/houses/{id}/images                 register what was uploaded
GET    /api/agency/houses/{id}/images
PUT    /api/agency/houses/{id}/images/{imageId}/cover
DELETE /api/agency/houses/{id}/images/{imageId}
```

### Agency leases - requires `AGENT` or `AGENCY_ADMIN`

```
POST /api/agency/leases                      sign a lease; 409 if the house is taken
GET  /api/agency/leases                      list own agency's leases, paged
GET  /api/agency/leases/{id}                 404 if it belongs to another agency
POST /api/agency/leases/{id}/activation      record that the renter moved in
POST /api/agency/leases/{id}/termination     end the lease, freeing the house
```

### Landlord portfolio - requires `LANDLORD`

```
GET /api/landlord/leases?currentOnly=&page=&size=
GET /api/landlord/leases/{leaseId}
```

No `landlordId` parameter anywhere: the identity comes from the `party_id` claim.
Accepting it from the path would let any landlord read every other landlord's
tenancies, and there is no tenant filter underneath to catch the mistake.

### Agency billing - requires `AGENT` or `AGENCY_ADMIN`

```
GET  /api/agency/invoices?unpaidOnly=&page=&size=
GET  /api/agency/invoices/overdue             the chase list
GET  /api/agency/invoices/{id}
POST /api/agency/payments                     record money already received
GET  /api/agency/payments
POST /api/agency/payments/{id}/settlement     confirm a provider callback
POST /api/agency/payments/{id}/failure        the provider rejected it
GET  /api/agency/payouts?pendingOnly=
POST /api/agency/payouts/{id}/settlement      record that the landlord was remitted
GET  /api/agency/settlement-config
PUT  /api/agency/settlement-config            AGENCY_ADMIN only
```

### Renter's own rent - requires `RENTER`

```
GET  /api/renter/invoices?unpaidOnly=&page=&size=
GET  /api/renter/invoices/{invoiceId}
GET  /api/renter/balance                      one number: what is owed
POST /api/renter/invoices/{invoiceId}/payments   start paying; lands PENDING
```

### Landlord's earnings - requires `LANDLORD`

```
GET /api/landlord/earnings?page=&size=
GET /api/landlord/earnings/total              net of commission
```

### Contacts - `notification-service`

```
GET   /api/me/contact                         LANDLORD or RENTER
PUT   /api/me/contact
PATCH /api/me/contact/preferences             mute one kind of mail
PUT   /api/contacts/{partyId}                 AGENCY_ADMIN, onboarding a landlord
GET   /api/contacts/{partyId}                 AGENCY_ADMIN
```

---

## "Only houses that are not rented get listed"

Two mechanisms, doing different jobs:

**The projection** (`ListingProjector`). A house reaches `marketplace_listing` only
when it is both `AVAILABLE` and `published`. Because the projection contains nothing
else, public search needs no availability condition - it is already true of every row.

**The constraint** (in `lease-service`). Availability is derived from leases, and
eventual consistency in the projection cannot by itself prevent two agents letting
the same house at the same instant. A PostgreSQL exclusion constraint makes it
physically impossible:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE lease ADD CONSTRAINT no_overlapping_active_lease
  EXCLUDE USING gist (
    house_id WITH =,
    daterange(start_date, end_date, '[)') WITH &&
  ) WHERE (status IN ('PENDING_MOVE_IN', 'ACTIVE'));
```

One transaction commits, the other gets a constraint violation mapped to `409`. No
distributed lock, no saga.

An agent no longer types a house's availability. `LeaseEventConsumer` derives it from
`LeaseSigned` / `LeaseActivated` / `LeaseEnded` and calls the projector, so a house
leaves the marketplace because a lease exists rather than because someone remembered.
The assertions in `MarketplaceVisibilityTest` are unchanged from Phase 1.

---

## How an event gets across

`lease-service` never calls `property-service`. A lease change and its event are
written in one transaction, and a relay moves the committed row to Kafka afterwards:

```
sign/activate/end ─── one transaction ──→ lease + outbox_event
                                                    │
                            OutboxRelay (2s poll) ──┘
                                     │
                            lease-events topic
                                     │
                 ┌───────────────────┴───────────────────┐
                 ▼                                       ▼
        property-service                          payment-service
        availability + listings                   billing replica → invoices
                                                          │
                                        one transaction ──┴──→ payment + outbox_event
                                                          │
                                            OutboxRelay ──┘
                                                          │
                                                 payment-events topic
                                                          │
                                                 notification-service
                                                 delivery log → email
```

Two consumers on `lease-events`, and neither knows about the other. Adding
`payment-service` required no change to `lease-service` at all - which is the entire
point of publishing events rather than making calls.

Publishing inside the transaction is not an option - the database write may still
fail. Publishing after commit loses the event if the process dies in between, leaving
a let house advertised forever. The outbox removes the gap: both rows commit or
neither does.

Delivery is therefore **at least once**, and every consumer handler sets an absolute
state instead of applying a delta, so a redelivery is a no-op.

Two things a consumer thread has that a request does not have for free:

- **No JWT**, so the tenant cannot be read from a token. Each handler runs inside
  `AgencyContext.runWith(...)` using the agency carried in the event itself.
- **No CDI request context**, and Quarkus only consults a `TenantResolver` when one is
  active - without it Hibernate is handed a null tenant and refuses to open a session
  at all. Hence `@ActivateRequestContext` on the consumers. Note this bites even for
  entities with no discriminator: it is the *session*, not the entity, that needs a
  tenant.

Quarkus's scheduler activates a request context for `@Scheduled` methods, so the jobs
work on their timers. But the same jobs are also called directly - by tests, and by any
future operational endpoint - so `payment-service` activates one itself in
`AgencyScan.inRequestContext`. A job that only works when its caller happens to have a
request context is a trap, not a design.

`notification-service` needs none of this. With no tenant to resolve, its consumer opens
a session on a Kafka thread without ceremony - which is a fair sign that the ceremony
elsewhere is the cost of multi-tenancy rather than of messaging.

---

## Authentication

Every service validates **bearer JWTs** issued by Keycloak. The realm, its roles, the
two claim mappers and six seeded users are imported automatically from
`infra/keycloak/` when the container starts, so there is nothing to click through:

```bash
docker compose up -d keycloak
```

### Two clients, because browsers and scripts need different flows

| Client | Flow | For |
|---|---|---|
| `houseagent-backend` | password grant, confidential | Postman, curl, tests - anywhere there is no browser to redirect |
| `houseagent-web` | authorization code + PKCE, public | the frontend |

The web client cannot use password grant, deliberately: a frontend must never see the
user's password, which is the whole point of redirecting to Keycloak. PKCE is mandatory
rather than optional, and redirect URIs are matched exactly. `docs/frontend.md` covers
what a frontend developer needs.

### The two claims everything turns on

A validated token is not enough. Authorisation needs to know *which agency* or *which
person* is asking, and those are custom claims mapped from user attributes:

| Claim | On | Used for |
|---|---|---|
| `agency_id` | AGENT, AGENCY_ADMIN | the `@TenantId` discriminator - every agency-scoped query |
| `party_id` | LANDLORD, RENTER | the explicit predicate in the four untenanted read paths |

Neither is ever read from a header, path or query parameter. `CallerContext` takes them
from the validated token and nowhere else, which is why `platform-admin` - a real user
with a real role and neither claim - gets 403 from both the agency and the personal
endpoints. A role says what kind of thing you may do; the claims say whose data it is.

### Seeded users

All six share the password `password`, because this realm is for development and says so
in its own name.

| User | Role | Claim |
|---|---|---|
| `agent-a` | AGENT | `agency_id=agency-a` |
| `admin-a` | AGENCY_ADMIN, AGENT | `agency_id=agency-a` |
| `agent-b` | AGENT | `agency_id=agency-b` |
| `landlord-one` | LANDLORD | `party_id=1111…` |
| `renter-one` | RENTER | `party_id=2222…` |
| `platform-admin` | PLATFORM_ADMIN | neither, deliberately |

`agent-b` exists so tenant isolation can be demonstrated with a real token rather than
an edited header: get their token, read agency-a's house, get a 404.

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/houseagent/protocol/openid-connect/token \
  -d grant_type=password -d client_id=houseagent-backend \
  -d client_secret=houseagent-dev-secret \
  -d username=agent-a -d password=password | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/agency/houses
```

Password grant rather than a browser flow: these are APIs, and `application-type:
service` means a request without a token gets a 401 rather than a redirect to a login
page no API client can follow.

### Running without Keycloak

Dev builds also accept three headers, so a service can be started and poked at with no
Docker at all:

```bash
curl -H 'X-Dev-Roles: AGENT' -H 'X-Dev-Agency: agency-a' \
     http://localhost:8081/api/agency/houses
```

Two things make that safe rather than a back door.

`DevIdentityAugmentor` carries `@IfBuildProfile("dev")` - a build-time condition, not a
runtime flag. In a production build the class is not present at all, so no misconfigured
environment variable can switch header-derived identity back on. The safety is in the
absence, not in the behaviour.

And **a real token always wins**. The augmentor returns immediately if the identity is
already authenticated, so headers can only ever fill a vacuum. Presenting `agent-b`'s
token alongside `X-Dev-Agency: agency-a` still answers 404, because the token decides.

Send no headers and no token and you are anonymous, which is exactly how the public
marketplace is meant to be used.

### Why the services depend on `SecurityIdentity`

`CallerContext` and `AgencyTenantResolver` read the identity, not `JsonWebToken`
directly. The latter is only a CDI bean when OIDC is switched on, so injecting it meant
every service failed to start the moment authentication was disabled - the exact mode a
developer wants to run in. In production the identity's principal *is* the JWT, so
nothing about deployed behaviour changes.

---

## The gateway

```bash
docker compose up -d gateway     # http://localhost:8000, dashboard on :8001
```

One origin in front of all five services, so a browser frontend has a single host to
talk to and CORS is configured once instead of four times.

It is **configuration, not a service anybody wrote**. Coming from Spring you would reach
for Spring Cloud Gateway - a Java application to build, test and deploy. Quarkus has no
equivalent on purpose: a gateway that only copies bytes between sockets is a reverse
proxy, and reverse proxies already exist. The routing table lives in
`infra/traefik/dynamic.yaml`; in a deployment it is replaced by an Ingress, and the
table is the part worth keeping.

### The routing table is not one prefix per service

Worth seeing, because it is the first thing a gateway makes visible:

| Path | Service |
|---|---|
| `/api/agency/houses`, `/api/marketplace` | property |
| `/api/agency/leases`, `/api/landlord/leases` | lease |
| `/api/agency/invoices`, `/api/agency/payments`, `/api/agency/payouts`, `/api/agency/settlement-config` | payment |
| `/api/renter`, `/api/landlord/earnings` | payment |
| `/api/me/contact`, `/api/contacts` | notification |

`/api/agency` is split across three services and `/api/landlord` across two, because the
paths were designed per service and audience rather than for routing. Traefik matches
longer rules first, so the specific prefixes win.

### It authenticates nothing

Every service still validates its own bearer token, and that is deliberate. A gateway
that authenticated and then forwarded "trusted" traffic would mean anything able to
reach the internal network could act as anyone - and here, where `agency_id` in a token
decides which customer's data comes back, that is the whole ballgame. The gateway
forwards the `Authorization` header and forms no opinion about it.

### Two things it deliberately does not route

**Keycloak.** A token's `iss` claim is built from the host it was requested through, so
issuing tokens via the gateway would stamp them with the gateway's address while the
services expect `localhost:8180` - and every service would reject a perfectly valid
token. Keycloak keeps its own port in both Postman environments.

**`/q/health` and `/q/openapi`.** A health check exists to tell you whether *one
instance* is up; asking a load balancer produces the least useful possible answer. The
collection calls these on the direct ports even when everything else goes through the
gateway, which is what the `*Direct` variables are for.

---

## House images

Cloudinary hosts the bytes. This service stores only a **public id** - a path like
`houses/{agencyId}/{houseId}/{uuid}` - and never a URL.

### The bytes never pass through the service

The obvious design accepts a multipart upload and forwards it. That sends every photo
across the network twice, holds several megabytes per concurrent upload, and gives an
otherwise stateless service a memory profile set by how many agents happen to be
photographing houses.

So the browser uploads directly, and this service's only job is to say *yes, that
upload is allowed, to exactly this path*:

```
1. POST .../images/upload-ticket   →  signature bound to houses/{agency}/{house}/{uuid}
2. browser  ──file──▶  Cloudinary
3. POST .../images                 →  echo the public id back; it is checked
```

Two round trips instead of one, in exchange for never buffering a photograph.

### Step 3 is a security check, not bookkeeping

By the time a client calls back, it is holding an arbitrary string. If registration
accepted any public id, an agency could name a competitor's photograph and attach it to
its own listing. So registration re-derives the prefix from the caller's own token and
refuses anything outside it - the same value the signature was bound to, checked twice
on purpose. `HouseImageTest` deletes that check and watches six tests fail.

### URLs are derived, never stored

A URL bakes in the cloud name, the transformation and the delivery host. Store it and
changing any of them becomes a migration. The public id is the durable fact; the rest is
a rendering decision made per request:

```
https://res.cloudinary.com/{cloud}/image/upload/c_fill,g_auto,w_400,h_300,q_auto,f_auto/{publicId}
```

`f_auto` serves AVIF or WebP where the browser accepts them and JPEG where it does not;
`q_auto` picks a quality per image. For a marketplace grid on a phone over mobile data,
that is the difference between a page that loads and one that does not.

An unconfigured account is not an error - uploads return `503` and the rest of the
service is unaffected. A catalogue of houses is useful without photographs, and refusing
to boot would make the whole service depend on an integration one endpoint needs.

---

## How rent is collected

Invoices are **derived, not entered**. `InvoiceGenerator` re-derives the whole schedule
of every active lease from its payment modality on each run, and inserts whatever is
missing between the lease start and a horizon 45 days out.

That sounds wasteful until you consider the obvious alternative - generate the next
invoice when the last one is paid - which breaks permanently the first time a run is
missed, because nothing ever notices the gap. Re-deriving is only safe because the
schedule is deterministic and one unique index makes a repeat impossible:

```sql
CREATE UNIQUE INDEX uq_invoice_lease_period ON invoice (lease_id, period_start);
```

Running twice, restarting mid-run, or replaying a `LeaseSigned` event all converge on
the same rows.

### Paying is two steps

A renter **initiates** and a provider **confirms**. Mobile money - Wave, Orange Money,
MTN MoMo, which is how rent is actually paid in this market rather than by card - is
asynchronous: the push reaches a handset and the callback arrives minutes later.
Collapsing those into one write would mark rent received the moment somebody pressed a
button. Cash over the counter skips the wait, because there is nothing to confirm.

Providers retry callbacks, so the provider's reference is the idempotency key: checked
before insert for the ordinary retry, and refused by a partial unique index for two
racing at once.

### Who holds the money is per agency

Both models exist in this market, so the platform does not pick one:

| Mode | What happens |
|---|---|
| `PLATFORM_COLLECTS` | Renter pays the agency, which keeps a commission and owes the landlord the rest. Every settlement writes a `payout`. |
| `DIRECT_TO_LANDLORD` | Renter pays the landlord; the platform only records it. No commission, no payout - the *absence* of a row is the record that nothing is owed. |

The default for an unconfigured agency is `DIRECT_TO_LANDLORD`: the mode where the
platform holds no money is the safe direction to be wrong in. Commission is stored in
basis points, and a check constraint refuses a commission on an agency that does not
collect - the platform cannot take a cut of money it never touches.

The branch is read exactly once, in `SettlementPolicy`, and everything downstream
consumes the result. `PaymentSettled` carries the commission and net amounts rather than
leaving consumers to compute them, so an email and a payout can never disagree.

### Money is never a double

Both services that read amounts from JSON enable `USE_BIG_DECIMAL_FOR_FLOATS`, and
format through `toPlainString()`. Jackson strips trailing zeros when parsing into a
BigDecimal, so `150000.00` becomes `1.5E+5` - which the notification tests caught being
mailed to a landlord.

---

## Tests

```
property-service
  AgencyIsolationTest               8 tests - the one that has to pass before anything else
  MarketplaceVisibilityTest        11 tests - cross-agency visibility and listing rules
  LeaseEventDrivenAvailabilityTest  7 tests - availability derived from lease events
  HouseImageTest                   13 tests - signed uploads, and the check that makes
                                              direct-to-Cloudinary upload safe

lease-service
  DoubleLettingTest                 7 tests - the exclusion constraint, through the API
  LandlordIsolationTest            12 tests - one landlord cannot see another's tenancies
  OutboxRelayTest                   6 tests - events are queued, relayed once, and never
                                              published for a lease that rolled back

payment-service
  RentCollectionTest               15 tests - the whole money loop, in the order it happens
  RenterIsolationTest              10 tests - one renter cannot see or pay another's rent
  PaymentIdempotencyTest            9 tests - the same money is never taken twice
  ArrearsTest                       5 tests - late rent is noticed once, not every run

notification-service
  LandlordNotificationTest          9 tests - the landlord actually gets a useful email
```

112 tests, none of which need Docker, Kafka, an SMTP server, a Cloudinary account or a
running sibling service.

The ones that carry the most weight:

`AgencyIsolationTest` asserts that agency B cannot list, read or edit agency A's
houses, that a missing house and someone else's house are indistinguishable (both
404, since 403 would confirm existence), and that a renter token is rejected outright
rather than silently falling back to a default tenant.

`LandlordIsolationTest` is its counterpart for the rule with no safety net. Landlords
are platform-wide, so `landlord_lease_view` has no `@TenantId` and a single `WHERE
landlord_id` predicate is the entire access control. It also pins the behaviour that
makes the projection necessary in the first place: one landlord's portfolio spans
every agency they work with.

`RenterIsolationTest` is the sharpest of the three isolation tests, because it covers
the write path as well as the read: it asserts that a renter cannot *pay* an invoice
they cannot see. A leak there would not merely expose data, it would let anyone spend
their own money settling a stranger's rent.

`PaymentIdempotencyTest` runs every route into a settlement twice. Providers retry
callbacks - that is documented behaviour, not an edge case - so "the same money is taken
once" is asserted rather than assumed, including that a *refused* retry leaves the
invoice untouched.

`LandlordNotificationTest` asserts on the message a landlord would actually have
received, not on the fact that a send was attempted. An email quoting two UUIDs at
somebody is a delivered notification and a useless one.

Every service swaps in the in-memory connector under `%test`, and the scheduler is
disabled outright rather than given long intervals - a job firing once unexpectedly is
how a test that pins three invoices finds twelve. Jobs are driven explicitly instead, so
a failure means a bug rather than a slow machine.

---

## Next

1. **Keycloak as the source of truth for contact details.** The realm issues tokens,
   but `notification-service` still keeps its own `contact` table; that should become a
   cache of Keycloak rather than the record.
2. **A real payment provider.** `PaymentMethod.MOBILE_MONEY` and the provider-reference
   idempotency are modelled; nothing yet talks to Wave or Orange Money, and there is no
   authenticated webhook endpoint for their callbacks.
3. **A dead-letter table** for events a consumer discards, which today are only logged.
4. **Orphaned-asset reconciliation.** A Cloudinary destroy that fails after its row is
   deleted leaves a file nobody references. The public id is logged, but nothing sweeps
   for them yet.
5. **Deposit lifecycle.** Captured at signing, never reconciled at lease end.
6. **Qute templates** for notifications, once there are more than two of them.
