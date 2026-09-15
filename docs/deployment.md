# Deployment

Putting the platform on a single VPS with Docker Compose.

The prod profiles were written before anything to run them existed, and they are
strict: no variable has a fallback, so a missing secret stops the boot naming the
property it wanted. Everything else in this document is the layer around them.

For local development read [`handover.md`](handover.md) instead. Nothing here applies to
a laptop.

---

## What this gives you, and what it does not

**Does:** the five services and the gateway as containers, TLS with automatic renewal,
PostgreSQL and Redpanda on an internal network with nothing but 80 and 443 exposed, and
Keycloak on its own hostname.

**Does not:** CI, centralised logs, metrics, alerting, automated backups, zero-downtime
deploys, or more than one instance of anything. Section 8 says what to do about that.

Suitable for a first production deployment carrying real but modest traffic. Money moves
through this system, so read section 7 before it carries anyone else's.

---

## 1. Size the machine

| | |
|---|---|
| **RAM** | 8 GB. Five JVMs at ~400 MB, Keycloak ~700 MB, Postgres ~500 MB, Redpanda capped at 1 GB |
| **vCPU** | 4 |
| **Disk** | 40 GB SSD |
| **OS** | Any current Linux with Docker Engine and the Compose plugin |

4 GB is not enough. Redpanda in particular sizes itself against the whole machine unless
told otherwise - `docker-compose.prod.yml` caps it at 1 GB for exactly this reason.

## 2. DNS

Three names, all pointed at the VPS:

| Name | Serves |
|---|---|
| `api.example.com` | The gateway - all five services |
| `auth.example.com` | Keycloak |
| `app.example.com` | Your frontend, wherever it is hosted |

Let's Encrypt resolves these during the HTTP challenge, so set them up **before** the
first `up -d` or certificate issuance fails and Traefik backs off.

## 3. Replace the example hostnames

Traefik's file provider does no variable substitution, so the hostnames are literals in
the routing table. Edit `infra/traefik/prod/dynamic.yaml`:

```bash
sed -i 's/api\.example\.com/api.yourdomain.com/g;
        s/auth\.example\.com/auth.yourdomain.com/g;
        s/app\.example\.com/app.yourdomain.com/g' infra/traefik/prod/dynamic.yaml
```

`app.example.com` appears once, in `accessControlAllowOriginList`. That is your
frontend's origin and it must be exact - a browser will refuse a credentialed request
against anything else, including a wildcard.

## 4. Fill in the secrets

```bash
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

Every commented block in that file says what the value is for and what goes wrong if it
is wrong. Leave `OIDC_CLIENT_SECRET` and `KEYCLOAK_ADMIN_CLIENT_SECRET` blank for now -
they come from the realm you have not built yet.

## 5. Build the images

Build the jars with the **prod profile**, then the images:

```bash
./mvnw clean package -DskipTests -Dquarkus.profile=prod
docker compose -f docker-compose.prod.yml --env-file .env.prod build
```

The profile matters beyond configuration. `DevIdentityAugmentor` carries
`@IfBuildProfile("dev")`, so a prod build omits the class entirely and the `X-Dev-Roles`
headers physically cannot work however the container is configured. A dev-built jar
deployed here keeps that path alive. The safety is in the absence.

Run `./mvnw clean install` once before `-DskipTests` — 112 tests, none needing Docker.
Skipping them on a build machine is fine; never skipping them is not.

## 6. Build the production realm

**The development realm cannot be deployed.** `infra/keycloak/` seeds six users whose
password is `password` and a client secret published in this repository, which is why
`docker-compose.prod.yml` neither mounts it nor passes `--import-realm`.

Start Postgres and Keycloak alone:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d postgres keycloak gateway
```

Then at `https://auth.yourdomain.com`, sign in with the bootstrap admin and create a
realm named `houseagent` carrying the same structure as the development one:

**Realm roles** — `PLATFORM_ADMIN`, `AGENCY_ADMIN`, `AGENT`, `LANDLORD`, `RENTER`.

**Three clients:**

| Client | Type | For |
|---|---|---|
| `houseagent-backend` | Confidential, direct access grants on | Scripts and the Postman collection |
| `houseagent-web` | Public, standard flow, PKCE | The browser frontend |
| `houseagent-admin` | Confidential, service account, no direct access | `agency-service` creating users |

**Two protocol mappers**, on the backend and web clients. Everything in the platform
turns on these: `agency_id` as a user attribute mapped into the access token, and
`party_id` likewise. Without them every token is valid and useless — the tenant resolver
fails closed with a sentinel matching no agency, so requests return empty rather than
wrong, but nothing works.

**`houseagent-admin`'s service account** gets `manage-users`, `view-users` and
`query-users` on the `realm-management` client. Nothing further. Losing that credential
should not be able to grant anybody a privilege the platform does not already hand out
through signup.

**Realm settings:** the password policy from commit `494a0ea`, email verification
required (only agency admins are asked to prove an address — `5a698cb`), login with
email enabled, and SMTP configured so verification mail is actually sent. Phone numbers
work as usernames because the number *is* the username; no configuration is needed
beyond allowing it.

**No users.** The first real agency arrives through `POST /api/signup`.

Copy the two generated secrets into `.env.prod`, then remove `KEYCLOAK_ADMIN_USER` and
`KEYCLOAK_ADMIN_PASSWORD` and restart Keycloak. A permanent bootstrap credential is a
permanent way in.

**Export the realm** and keep it somewhere safe. It is now the only copy:

```bash
docker compose -f docker-compose.prod.yml exec keycloak \
  /opt/keycloak/bin/kc.sh export --realm houseagent --file /tmp/realm.json
docker compose -f docker-compose.prod.yml cp keycloak:/tmp/realm.json ./realm-prod.json
```

That file contains client secrets. Do not commit it.

## 7. Start everything

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

Compose handles the ordering: Postgres is health-gated, and the services wait on it and
on Redpanda. Flyway runs at startup and takes a lock, so several services migrating at
once is safe.

Watch the first boot — this is where a wrong variable announces itself:

```bash
docker compose -f docker-compose.prod.yml logs -f
```

## 8. Verify

Four checks, each isolating one layer, so the first failure tells you where the problem
is.

**1. Each service is up.** They publish no ports, so ask from inside:

```bash
for s in property lease payment notification agency; do
  echo -n "$s: "
  docker compose -f docker-compose.prod.yml exec -T $s-service \
    curl -sf localhost:808$((1 + $(echo "property lease payment notification agency" | tr ' ' '\n' | grep -n "^$s$" | cut -d: -f1) - 1))/q/health \
    > /dev/null && echo UP || echo DOWN
done
```

**2. TLS and routing.** From your own machine:

```bash
curl "https://api.yourdomain.com/api/marketplace/listings?city=Abidjan&country=CI"
```

200 with a JSON page. An empty `items` array is a pass — the route worked and nothing is
listed yet.

**3. Tokens.** Create a throwaway user in the realm, then:

```bash
curl -X POST https://auth.yourdomain.com/realms/houseagent/protocol/openid-connect/token \
  -d grant_type=password -d client_id=houseagent-backend \
  -d client_secret=$OIDC_CLIENT_SECRET \
  -d username=... -d password=...
```

Decode the result at jwt.io and confirm **`iss` exactly matches `OIDC_AUTH_SERVER_URL`**.
If it does not, fix it now — every service will reject these tokens, and the error says
nothing useful.

**4. The domain.** Point the Postman collection at the new host and walk
[`user-guide.md`](user-guide.md) from signup through to a landlord being emailed. Use a
real address you control for that last step.

---

## Operations

### Deploying a change

```bash
git pull
./mvnw clean package -DskipTests -Dquarkus.profile=prod
docker compose -f docker-compose.prod.yml --env-file .env.prod build property-service
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d property-service
```

One service at a time. There is a gap of a few seconds where that service returns 502
while the others keep serving — the cost of running one instance of each.

Set `TAG` to something you can name rather than `latest`. Rolling back means knowing
what the previous one was.

### Backups

Nothing does this for you. At minimum, nightly:

```bash
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dumpall -U houseagent | gzip > backup-$(date +%F).sql.gz
```

**Take it off the VPS.** A backup on the machine it protects is not a backup. Include
`realm-prod.json` and `.env.prod` in whatever you copy — losing the realm means every
account is gone.

Restore is `gunzip -c backup.sql.gz | docker compose exec -T postgres psql -U houseagent`.
Test it once, on a throwaway machine, before you need it.

### Logs

```bash
docker compose -f docker-compose.prod.yml logs -f payment-service
```

Add rotation, or the disk fills:

```json
{ "log-driver": "json-file", "log-opts": { "max-size": "50m", "max-file": "5" } }
```

in `/etc/docker/daemon.json`.

---

## What to fix before this carries real money

In order:

1. **Backups, off the machine, tested.** Everything else is recoverable; data is not.
2. **A real payment provider.** `MOBILE_MONEY` and provider-reference idempotency are
   modelled, but nothing talks to Wave or Orange Money and there is no authenticated
   webhook endpoint for their callbacks. Until then no money actually moves.
3. **A dead-letter table.** Events a consumer discards are only logged, so a bad message
   is invisible unless somebody is reading logs.
4. **CI.** The 112 tests and `check_routes.py` run only when a human remembers.
5. **Monitoring.** `/q/health` exists on all five services and nothing watches it. A
   container that dies at 3am stays dead until somebody notices.
6. **Firewall.** Only 80, 443 and SSH. Postgres, Keycloak's HTTP port and Redpanda are
   on the internal bridge and should never be reachable from outside.

---

## The failures you are most likely to hit

### Every request 401s with a valid-looking token

`iss` mismatch — see check 3 above. `OIDC_AUTH_SERVER_URL`, `KEYCLOAK_HOSTNAME` and the
`Host()` rule for Keycloak must all describe the same URL.

### The frontend gets CORS errors

`accessControlAllowOriginList` in `infra/traefik/prod/dynamic.yaml` still says
`app.example.com`, or names a different scheme or port from the real origin.

### 502 from the gateway

Traefik matched but the service is not answering. `docker compose ps` for what is
actually running, then that service's logs.

### A 404 in plain text rather than the platform's JSON error shape

No router matched — the 404 came from Traefik, not a service. Usually a new endpoint
whose prefix nothing routes. `/api/agency` is split across **three** services, so adding
`/api/agency/something` needs a new rule.

### Certificate issuance fails

DNS is not pointing at the VPS yet, or port 80 is blocked — the HTTP challenge needs it
even though everything else redirects to HTTPS. Traefik backs off after repeated
failures; fix DNS, then `docker compose restart gateway`.

### lease-service will not start

`btree_gist`. The migrating role must be able to `CREATE EXTENSION`, or the extension
must already exist in `houseagent_lease`. Common on managed PostgreSQL.

### No email arrives

`MAIL_*` credentials, or a provider that does not support both STARTTLS and login —
`application-prod.yaml` requires both, and `mock` is false, which is the one setting that
must never change. A mock mailbox in production swallows every notification while looking
perfectly healthy.
