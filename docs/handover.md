# Handover

Everything needed to take this project over: getting it running on a machine that has
never seen it, the loop you work in day to day, what breaks and why, and where the work
stopped.

This document does not explain the architecture or the domain. Three others already do,
and duplicating them here would mean two descriptions drifting apart:

| Read | When |
|---|---|
| **This file** | You have just been handed the repo and need it running |
| [`../README.md`](../README.md) | You need to know *why* it is built this way - the five isolation rules, events, money, the gateway |
| [`user-guide.md`](user-guide.md) | You need the domain: an agency lists a house, a lease is signed, rent is invoiced and paid, the landlord is emailed |
| [`frontend.md`](frontend.md) | You are building a client against the API |
| [`deployment.md`](deployment.md) | You are putting it on a server |

Read this one first, then `user-guide.md`. Between them you can run the system and know
what it is for. `README.md` is the one to read slowly, and it rewards that.

---

## 1. What you have been given

A multi-agency house rental platform: five Quarkus microservices, a Traefik gateway, a
Keycloak realm, and a Postman collection covering all 59 endpoints.

| Module | Port | Owns |
|---|---|---|
| `property-service` | 8081 | Houses, images, marketplace listings |
| `lease-service` | 8082 | Leases, payment modality, availability |
| `payment-service` | 8083 | Invoices, payments, commission, payouts |
| `notification-service` | 8084 | Contact details, email delivery and retry |
| `agency-service` | 8085 | Signup, subscription plans, staff, onboarding |
| `common` | - | Shared code. Not a service, has no port |

All five are marked **Complete** in the README. 112 tests pass. Section 8 lists what was
deliberately left undone.

### Repository layout

```
houseagentassistant/
├── README.md                  architecture and rationale - the long one
├── docs/
│   ├── handover.md            this file
│   ├── user-guide.md          the domain, as a story, with real responses
│   └── frontend.md            client integration
├── pom.xml                    Maven reactor: common + the five services
├── docker-compose.yml         gateway, postgres, keycloak, redpanda, mailpit
├── .env.example               template for secrets - copy, never commit
├── infra/
│   ├── traefik/dynamic.yaml   the gateway routing table
│   ├── keycloak/              realm export, imported on container start
│   └── postgres-init/         creates one database per service
├── postman/
│   ├── houseagentassistant.postman_collection.json
│   ├── local-dev.postman_environment.json      → the five ports directly
│   ├── gateway.postman_environment.json        → localhost:8000
│   ├── build_collection.py    regenerates the collection
│   └── check_routes.py        fails if the collection has drifted from the code
└── <service>/
    └── src/main/resources/
        ├── application.yaml       true everywhere, plus the "%test" block
        ├── application-dev.yaml   a laptop
        └── application-prod.yaml  a deployment
```

---

## 2. Day one: from nothing to a working system

Roughly 30 minutes on a clean machine. Do these in order - each step assumes the one
before it.

### 2.1 Install the prerequisites

| Need | Why | Check |
|---|---|---|
| **JDK 21+** | `maven.compiler.release` is 21 | `java -version` |
| **Docker Desktop** | Gateway, Keycloak, Redpanda, Mailpit | `docker version` |
| **PostgreSQL 18** | The five databases | `psql --version` |
| **Python 3** | Only for the two Postman scripts | `python --version` |
| **Postman** | Trying the API by hand | - |

Maven is not in that list: the repo ships the wrapper. Use `./mvnw` (or `.\mvnw.cmd` on
Windows) and you get the right version automatically.

### 2.2 Decide where PostgreSQL comes from

There are two options and **they cannot both own port 5432**. This trips people up, so
decide now:

- **A local PostgreSQL install** (how the project is currently configured - the
  `postgresql-x64-18` Windows service on 5432). Nothing to change.
- **The container** in `docker-compose.yml`, which publishes **5433** to avoid the
  clash. If you choose this, change the port to 5433 in every service's
  `application-dev.yaml`, or stop the local service first.

The warning at the top of `docker-compose.yml` says the same thing. Take it seriously -
a half-migrated database pointing at the wrong server produces confusing failures.

### 2.3 Create the secrets files

`.env` is gitignored and **was not handed to you** - it holds passwords. Quarkus dev
mode reads `.env` from the module working directory, so each service needs its own copy:

```bash
cp .env.example .env                        # then fill in DB_PASSWORD
cp .env property-service/.env
cp .env lease-service/.env
cp .env payment-service/.env
cp .env notification-service/.env
cp .env agency-service/.env
```

PowerShell:

```powershell
Copy-Item .env.example .env
# edit .env, then:
'property-service','lease-service','payment-service','notification-service','agency-service' |
  ForEach-Object { Copy-Item .env $_\.env }
```

Only `DB_PASSWORD` is genuinely required. `.env.example` documents the rest; the
Cloudinary keys can stay blank - uploads return 503 and everything else works.

### 2.4 Create the databases

Ten of them: each service owns one, plus a test database that is wiped and re-migrated
on every run. Never joined across - a service needing another's data asks over REST or
listens for its events.

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

The lease schema needs the **`btree_gist`** extension for the exclusion constraint that
stops a house being let twice. The migration creates it, which means the connecting user
must be a superuser or have the extension installed already. If you are using the
container, `infra/postgres-init/` handles the database creation for you.

### 2.5 Build and run the tests

```bash
./mvnw clean install
```

This is the real checkpoint. 112 tests, and **none of them need Docker, Kafka, SMTP, a
Cloudinary account or a sibling service** - every service swaps in an in-memory
connector under `%test`. So a failure here is your database setup or your JDK, not
missing infrastructure.

Green means the code is sound on your machine before any container exists.

### 2.6 Start the infrastructure

```bash
docker compose up -d
```

Five containers:

| Container | Port | What for |
|---|---|---|
| `hah-gateway` | 8000 API, 8001 dashboard | Traefik, one origin in front of all five services |
| `hah-postgres` | 5433 | Only if you chose the container over a local install |
| `hah-keycloak` | 8180 | Issues tokens; imports the realm on start |
| `hah-redpanda` | 9092, console 8090 | The event backbone |
| `hah-mailpit` | 1025 SMTP, 8025 web | Catches every outbound email |

Wait for Keycloak - it takes 30-60s to import the realm, and services that start before
it will fail to fetch the OIDC configuration.

### 2.7 Start the services

Each runs on the host under dev mode, not in a container. The gateway reaches back out
to them via `host.docker.internal`.

```bash
cd property-service && ../mvnw quarkus:dev
```

Running more than one at a time needs **distinct debugger ports**. Quarkus binds 5005 by
default and the second service to start dies with `transport error 202: bind failed`:

```bash
cd lease-service        && ../mvnw quarkus:dev -Ddebug=5006
cd payment-service      && ../mvnw quarkus:dev -Ddebug=5007
cd notification-service && ../mvnw quarkus:dev -Ddebug=5008
cd agency-service       && ../mvnw quarkus:dev -Ddebug=5009
```

Five terminals. There is no script that starts them all, deliberately - you usually want
one or two, and dev mode is most useful in the foreground where you can see the reload.

### 2.8 Import the Postman collection

`Ctrl+O` in Postman, then add **three** files - the collection and *both* environments:

```
postman/houseagentassistant.postman_collection.json
postman/local-dev.postman_environment.json       → "houseagentassistant - direct to services"
postman/gateway.postman_environment.json         → "houseagentassistant - through the gateway"
```

The environments land under **ENVIRONMENTS** in the sidebar, not under Collections. If
that section is empty you imported the collection only, and every `{{propertyUrl}}` will
fall back to the collection-level variable. Select one from the dropdown at top right -
it reads *No environment* until you do.

The collection is identical under both. Only the base URLs differ, which is the point:
the same requests prove the gateway is a pass-through.

### 2.9 Verify it end to end

Four checks, in this order. Each one isolates a different layer, so the first failure
tells you where the problem is.

**1. A service is alive** - `Health and API docs` → `property - health`, or:

```bash
curl http://localhost:8081/q/health
```

**2. The gateway routes** - select the gateway environment, then
`property-service (8081)` → `Public marketplace (anonymous)` → `Search listings`:

```bash
curl "http://localhost:8000/api/marketplace/listings?city=Abidjan&country=CI"
```

A 200 with a JSON page. An empty `items` array is still a pass - it means the route
worked and nothing is listed yet. You can also open **http://localhost:8001** and check
all eleven file routers are green.

**3. Keycloak issues tokens** - any request in the `Authentication - get a token`
folder. It saves `accessToken` into the environment, so everything after it is
authenticated automatically.

**4. The whole domain works** - follow [`user-guide.md`](user-guide.md) from section 1
to section 13. It walks an agency listing a house through to a landlord being emailed,
with the real responses at each step. **It doubles as the manual smoke test**, and it is
the fastest way to learn the system. Budget an hour.

---

## 3. The development loop

### Dev mode does the reloading

`quarkus:dev` recompiles on the next request after you save. You rarely restart. It also
serves:

| | |
|---|---|
| Swagger UI | http://localhost:8081/q/swagger-ui |
| Dev UI | http://localhost:8081/q/dev |
| Health | http://localhost:8081/q/health |
| OpenAPI | http://localhost:8081/q/openapi |

Substitute the port for other services.

### Working without Docker at all

Dev builds accept three headers, so you can start one service and poke at it with no
Keycloak, no gateway, nothing:

```bash
curl -H 'X-Dev-Roles: AGENT' -H 'X-Dev-Agency: agency-a' \
     http://localhost:8081/api/agency/houses
```

This is safe rather than a back door, for two reasons worth understanding before you
touch it. `DevIdentityAugmentor` carries `@IfBuildProfile("dev")` - a **build-time**
condition, so in a production build the class does not exist and no environment variable
can switch it back on. And **a real token always wins**: the augmentor returns
immediately if the identity is already authenticated, so headers only ever fill a
vacuum. Keep both properties if you modify it.

### Seeded users

Six, all with password `password`, because the realm is for development and says so in
its name:

| User | Role | Claim |
|---|---|---|
| `agent-a` | AGENT | `agency_id=agency-a` |
| `admin-a` | AGENCY_ADMIN, AGENT | `agency_id=agency-a` |
| `agent-b` | AGENT | `agency_id=agency-b` |
| `landlord-one` | LANDLORD | `party_id=1111…` |
| `renter-one` | RENTER | `party_id=2222…` |
| `platform-admin` | PLATFORM_ADMIN | neither, deliberately |

`agent-b` exists so isolation can be demonstrated with a real token rather than an
edited header: get their token, read agency-a's house, get a 404.

Everybody can sign in with **a phone number or an email**. The number is their Keycloak
username, so both reach the same account.

### Running tests

```bash
./mvnw test                                    # everything
./mvnw test -pl payment-service                # one service
./mvnw test -pl property-service -Dtest=AgencyIsolationTest
```

The isolation tests are the ones that matter most - `AgencyIsolationTest`,
`LandlordIsolationTest`, `RenterIsolationTest`. Four of the five read paths run against
tables with **no tenant discriminator**, so a query that forgets its `WHERE` returns
everyone's data and nothing fails. Those tests are the only thing that notices. The
README explains each in detail; do not weaken them.

### If you change an endpoint

The Postman collection is generated, not hand-edited:

```bash
python postman/build_collection.py    # regenerate after adding or renaming a route
python postman/check_routes.py        # compare every request against the @Path annotations
```

`check_routes.py` is the guard - a collection that drifts is documentation that lies.
Run it before you commit an API change. There is **no CI**, so nothing runs it for you.

### Conventions

Commit subjects are written as sentences describing behaviour, not as change logs -
"Sign in with a phone number as well as an email", "Only agency admins have to prove
their email address". `git log --oneline` reads as the story of the system. Thirteen
commits, each a complete capability.

The comments in this codebase explain **why**, not what, and several encode decisions
that are expensive to rediscover - `infra/traefik/dynamic.yaml` and the header block of
`docker-compose.yml` especially. If you change one of those decisions, change its
comment in the same commit.

---

## 4. Configuration

Each service has three YAML files, and which one you open tells you what you are looking
at:

| File | Holds |
|---|---|
| `application.yaml` | True everywhere - schema strategy, topic names, serializers - plus the `"%test"` block |
| `application-dev.yaml` | A laptop: localhost database, local Keycloak, SQL logging, Mailpit |
| `application-prod.yaml` | A deployment: everything from the environment, TLS required |

Test overrides live in the shared file rather than an `application-test.yaml`, because
tests are a variation on the shared configuration rather than a deployment target.

The important difference between dev and prod is **not which values they hold but
whether they have fallbacks**. Dev is full of `${DB_PASSWORD:postgres}`; prod is full of
bare `${DB_PASSWORD}`. A missing secret in production should stop the boot naming the
property it wanted, rather than quietly connecting as `postgres/postgres`:

```bash
java -Dquarkus.profile=prod -jar payment-service/target/quarkus-app/quarkus-run.jar
# 'quarkus.oidc.auth-server-url' property must be configured
```

Preserve that property when you add configuration. It is the difference between a
deployment that fails loudly and one that fails silently.

---

## 5. Troubleshooting

The failures you are actually going to hit, with what each one means.

### `ECONNREFUSED 127.0.0.1:8081`

Nothing is listening. The service is not running - this is not auth, not routing, not
CORS. Start it with `../mvnw quarkus:dev` from that service's folder.

Note that `property - health` and the OpenAPI requests use `{{propertyDirect}}`, pinned
to `:8081` in **both** environments by design, so switching to the gateway environment
will not change this error. Health checks deliberately bypass the gateway: a health
check exists to tell you whether *one instance* is up, and asking a load balancer
produces the least useful possible answer.

Which ports are actually listening:

```powershell
foreach ($p in 8000,8081,8082,8083,8084,8085,8180) {
  if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { "$p up" } else { "$p closed" }
}
```

### `open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`

Docker Desktop is not running. Launch it, wait for the tray whale to settle, confirm
with `docker version`. Every `docker compose` command fails this way until it is up.

### `502 Bad Gateway` from the gateway

Traefik matched a route but could not reach the service behind it. The service is down,
or `host.docker.internal` is not resolving. Remember the services run on the **host**
under dev mode, not in containers - `infra/traefik/dynamic.yaml` points at
`host.docker.internal:8081-8085`.

### `404` from the gateway, in plain text rather than your service's JSON error shape

No router matched. Check http://localhost:8001 for which rules loaded. The most likely
cause is a new endpoint whose prefix nothing routes - `/api/agency` is deliberately
**split across three services**, so adding `/api/agency/something` requires a new rule.
It is not one prefix per service, and that is intentional.

### `transport error 202: bind failed`

Two services both trying to bind debugger port 5005. Start the second with
`-Ddebug=5006`.

### Port 5432 already in use, or migrations hitting an unexpected schema

The local PostgreSQL service and the container both want 5432; the container publishes
5433 to sidestep it. See section 2.2 - pick one.

### `Extension "btree_gist" is not available`

The lease migration needs it and the connecting user lacks the rights. Connect as a
superuser or install the extension first.

### `401` on the public marketplace

The collection sets bearer auth at the root, so every folder inherits it. A stale
`accessToken` variable sends a bad token to an endpoint that wants none. Set that
request's **Authorization** tab to **No Auth**, or clear the variable.

### Tokens rejected as invalid despite being freshly issued

A token's `iss` claim is built from the host it was requested through. Keycloak is
deliberately **not routed** through the gateway for exactly this reason - request a
token via `:8000` and it is stamped with the gateway's address while the services expect
`localhost:8180`, and all five reject a perfectly valid token. Always go to Keycloak on
its own port.

### An email never arrives

Nothing leaves your machine. Mailpit catches everything - read it at
http://localhost:8025.

### Something in the domain looks wrong

[`user-guide.md`](user-guide.md) ends with a *"Where to look when something seems
wrong"* section covering the domain-level surprises: a house vanishing from the
marketplace when it is let, invoices appearing on their own, paying being two steps.

---

## 6. Where the state lives

Useful when something is wrong and you need to look at it directly, or when you want a
clean slate.

| State | Where | Reset |
|---|---|---|
| Business data | Five PostgreSQL databases | Drop and recreate; migrations run on start |
| Users, roles, claims | Keycloak realm | `docker compose down -v` then `up` re-imports `infra/keycloak/` |
| Events in flight | Redpanda topics | Inspect at http://localhost:8090 |
| Sent email | Mailpit, in memory | http://localhost:8025, or restart the container |
| House images | **Cloudinary - a real external account** | Not local, and not reset by anything here |

Cloudinary is the one piece of state that outlives your machine. The bytes never pass
through the service - uploads go browser-direct with a signature the service generates -
so images uploaded during testing are in a real account. `README.md` covers the flow and
the security check that makes it safe.

---

## 7. Deployment

[`deployment.md`](deployment.md) covers a single VPS with Docker Compose, end to end.
What exists for it:

- `Dockerfile.jvm` per service, under `src/main/docker/`
- `docker-compose.prod.yml` - the five services, gateway, Postgres, Keycloak and
  Redpanda on an internal network, with only 80 and 443 published
- `.env.prod.example` - every variable the prod profiles demand, annotated
- `infra/traefik/prod/dynamic.yaml` - the same routing rules with TLS, a real CORS
  origin, and Keycloak on its own hostname
- `application-prod.yaml` per service, reading everything from the environment, with no
  fallbacks

Two things to understand before you run any of it. **Build with the prod profile** -
`DevIdentityAugmentor` is `@IfBuildProfile("dev")`, so a prod build omits the class and
the `X-Dev-*` headers cannot work; a dev-built jar keeps that path alive. And **the
realm in `infra/keycloak/` cannot be deployed** - it seeds six users whose password is
`password`. `deployment.md` section 6 lists what to rebuild by hand.

Still missing: CI, centralised logs, metrics, alerting, automated backups, and any
second instance of anything.

---

## 8. Where the work stopped

From the README's `Next` section, in the order I would tackle them:

1. **A real payment provider.** `PaymentMethod.MOBILE_MONEY` and provider-reference
   idempotency are modelled, but nothing talks to Wave or Orange Money, and there is no
   authenticated webhook endpoint for their callbacks. This is the largest remaining
   piece and the one the product needs most.
2. **Keycloak as the source of truth for contact details.** The realm issues tokens, but
   `notification-service` still keeps its own `contact` table. That should become a
   cache of Keycloak rather than the record.
3. **A dead-letter table** for events a consumer discards. Today they are only logged.
4. **Orphaned-asset reconciliation.** A Cloudinary destroy that fails after its row is
   deleted leaves a file nobody references. The public id is logged; nothing sweeps.
5. **Deposit lifecycle.** Captured at signing, never reconciled at lease end.
6. **Qute templates** for notifications, once there are more than two.

Two things I would add that are not on that list: **CI**, so `check_routes.py` and the
112 tests run on every push rather than by hand, and **Dockerfiles** for the five
services, which is the first real step toward a deployment.

---

## 9. The five things worth knowing before you change anything

If you read nothing else here, read this.

1. **Four of the five read paths have no safety net.** Only the agent dashboard is
   protected by Hibernate's `@TenantId` filter. The marketplace, the landlord portfolio,
   the renter's payment history and the landlord's earnings all run against tables with
   no discriminator, where a forgotten `WHERE` predicate returns everyone's data and
   nothing fails. The isolation tests are the only thing standing there.

2. **The renter rule is the sharp one.** An invoice a renter can see is an invoice a
   renter can pay. A leak there does not merely expose data - it lets someone spend their
   own money settling a stranger's rent.

3. **A missing house and someone else's house are both 404.** Never 403, which would
   confirm the house exists. Keep that when you add endpoints.

4. **The gateway authenticates nothing, deliberately.** Every service validates its own
   token. A gateway that authenticated and forwarded "trusted" traffic would mean
   anything reaching the internal network could act as anyone - and here, `agency_id` in
   a token decides which customer's data comes back.

5. **`agency_id` and `renter_id` are never interchangeable.** The first is the
   multi-tenancy discriminator; the second is a domain foreign key. Renters and
   landlords are platform-wide principals - a renter may rent from agency A this year and
   agency B next - which is why their tokens carry no `agency_id`, and why
   `AgencyTenantResolver` fails closed with a sentinel matching no agency rather than
   falling back to a default.
