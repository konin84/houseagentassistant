# Frontend integration

Everything a browser client needs to talk to this platform. If you only read one
section, read [What the two claims mean](#what-the-two-claims-mean) - most of the
confusing responses come from there.

---

## Where things are

Locally, with `docker compose up -d gateway keycloak` and the services running:

| | URL |
|---|---|
| API | `http://localhost:8000` |
| Keycloak | `http://localhost:8180/realms/houseagent` |
| Gateway dashboard | `http://localhost:8001` |

**Use the gateway, not the service ports.** One origin means CORS is configured once,
and it is the only address that stays true when the services move. The individual ports
(8081-8085) exist for backend debugging and will not be reachable in a deployment.

---

## Logging in

Authorization code flow with PKCE, against the `houseagent-web` client.

| | |
|---|---|
| Issuer | `http://localhost:8180/realms/houseagent` |
| Client ID | `houseagent-web` |
| Client secret | none - it is a public client |
| Redirect URIs | `http://localhost:3000/*`, `http://localhost:5173/*` |
| Scopes | `openid profile email` |

Any OIDC library will do this for you - `oidc-client-ts`, `react-oidc-context`,
`angular-auth-oidc-client`, `keycloak-js`. Point it at the issuer and it discovers the
rest from `/.well-known/openid-configuration`.

```ts
// oidc-client-ts
const userManager = new UserManager({
  authority: "http://localhost:8180/realms/houseagent",
  client_id: "houseagent-web",
  redirect_uri: "http://localhost:3000/callback",
  response_type: "code",
  scope: "openid profile email",
});
```

Then send the **access token** - not the ID token - as a bearer header:

```
Authorization: Bearer <access_token>
```

### Three things the realm will refuse

These are deliberate, so you know what you are looking at when they happen:

- **PKCE is mandatory.** Omitting `code_challenge` redirects back with
  `error=invalid_request&error_description=Missing+parameter:+code_challenge_method`.
  Any current OIDC library sends it automatically.
- **Redirect URIs are matched exactly.** An unregistered one is a `400 Invalid
  parameter: redirect_uri` on Keycloak's own page - not a redirect, because sending an
  error to an unverified address is how tokens get stolen. Ask us to add yours; do not
  work around it.
- **Password grant is off** for this client: `unauthorized_client - Client not allowed
  for direct access grants`. A frontend must never see the user's password, which is
  the entire point of redirecting to Keycloak. The separate `houseagent-backend` client
  allows it for Postman and curl, where there is no browser to redirect.

### Development users

All six share the password `password`.

| User | Role | Sees |
|---|---|---|
| `agent-a` | AGENT | agency-a's houses, leases, invoices |
| `admin-a` | AGENCY_ADMIN | the same, plus deleting houses and setting commission |
| `agent-b` | AGENT | agency-b - useful for proving isolation in the UI |
| `landlord-one` | LANDLORD | their own portfolio and earnings |
| `renter-one` | RENTER | their own invoices, and can pay them |
| `platform-admin` | PLATFORM_ADMIN | deliberately nothing - see below |

### Where accounts come from

Nobody self-registers, and the frontend has no sign-up screen to build. Every account is
created by somebody one level out:

| Creating | Done by | Endpoint |
|---|---|---|
| An agency and its first admin | `PLATFORM_ADMIN` | `POST /api/platform/agencies` |
| An agent | `AGENCY_ADMIN` | `POST /api/agency/staff` |
| A landlord or renter | `AGENCY_ADMIN` | `POST /api/agency/landlords`, `/renters` |

Two things about that matter for the UI.

**Onboarding returns a `partyId`**, and that is what you then send as `landlordId` when
creating a house or `renterId` when signing a lease. There is nowhere else to get one.

**Onboarding an existing person answers `200`, not `201`,** with `linked: true` and no
password. That is success, not a conflict - a landlord who already works with another
agency is one person with one portfolio. Show "linked to an existing account" rather
than an error.

New accounts return a **one-time temporary password** in the response. It is shown once,
and the account cannot be used until the person sets their own - so a UI should display
it clearly enough to be written down, and warn that it will not be shown again.

---

## What the two claims mean

A valid token is **not** enough to see data. Alongside the roles, tokens carry one of
two custom claims, and they decide *whose* data comes back:

| Claim | On | Meaning |
|---|---|---|
| `agency_id` | AGENT, AGENCY_ADMIN | which agency's data every `/api/agency/**` call returns |
| `party_id` | LANDLORD, RENTER | which person `/api/renter/**`, `/api/landlord/**` and `/api/me/**` are about |

Both are already in the access token; decode it if you want to show the agency name or
branch on role. **Never send either as a parameter** - there is no endpoint that accepts
one, by design. `GET /api/landlord/leases?landlordId=...` would let any landlord read
every other landlord's tenancies, so the id comes from the token and nowhere else.

`platform-admin` exists to make this concrete: a real user, a real role, and neither
claim - so it gets `403` from both the agency and the personal endpoints. If you see a
`403` on an endpoint the user's role should allow, the claim is what is missing.

---

## Reading the responses

### Status codes mean specific things

| Code | Means | What the UI should do |
|---|---|---|
| `401` | No token, expired token, or an invalid one | Refresh, or send them back to login |
| `403` | Authenticated, but the wrong role - or the right role without the claim | Do not retry. Hide the control that produced it |
| `404` | Not found **or** belongs to another agency | Treat as not found. Do not say "no permission" |
| `409` | The request was valid but the world moved | Re-read and show the user what changed |
| `503` | An integration is unconfigured, currently only image uploads | Hide the upload UI rather than showing an error |

The `404`-for-someone-else's-data is deliberate throughout. A `403` would confirm that a
record with that id exists, which is itself a leak, so a foreign house and a nonexistent
house are indistinguishable. Do not build a UI that tries to tell them apart.

`409` is worth handling properly rather than as a generic failure. It is the normal
answer to two real situations: signing a lease for a house someone else just took
(`HOUSE_ALREADY_LET`), and recording a payment whose provider reference was already
used (`PAYMENT_ALREADY_RECORDED`). Both mean *re-read and try again*, not *something
broke*.

### Every error has the same shape

```json
{
  "code": "HOUSE_ALREADY_LET",
  "message": "House a8b3... already has a lease covering that period",
  "details": [{ "field": "dueDayOfMonth", "message": "must be less than or equal to 28" }],
  "timestamp": "2026-08-19T16:12:04.881Z"
}
```

Branch on `code`, never on `message` - the message is for humans and will change.
`details` is populated only for `VALIDATION_FAILED`, where each entry names the field
that failed so you can attach it to the right input.

### Every list has the same shape

```json
{ "items": [], "page": 0, "size": 20, "totalItems": 0, "totalPages": 0 }
```

`?page=` is zero-based and `?size=` is capped at 100 whatever you ask for.

---

## Money and dates

**Amounts are JSON numbers with two decimals**, written plainly:

```json
{ "pricePerMonth": 150000.00, "commissionAmount": 11250.00, "netAmount": 138750.00 }
```

Two consequences worth knowing.

`JSON.parse` turns that into a JavaScript number, so `150000.00` becomes `150000` and
the decimals are gone from the value itself. Format with
`Intl.NumberFormat(locale, { minimumFractionDigits: 2 })` rather than relying on what
came over the wire.

And a JS number is a double. At these magnitudes that is exact, so displaying and
comparing are safe - but totalling a page of invoices in floats is the habit that
eventually costs a centime. Sum with a decimal library, or convert to minor units,
if you are adding many of them.

When you *send* an amount, a JSON string is accepted and is the safer habit:
`"amount": "150000.00"` avoids your own float ever touching the value.

The currency is per house and per lease rather than global - agencies may operate across
borders - so read it from the response rather than hardcoding `XOF`.

Dates are `YYYY-MM-DD`, timestamps are ISO-8601 UTC with a `Z`.

`dueDayOfMonth` is validated to 1-28. Not an oversight: a lease due on the 31st has no
due date in February, and every scheme for papering over that afterwards is worse than
not allowing it. Cap the input at 28 rather than letting the user find out by rejection.

---

## Images

House photographs are hosted by Cloudinary and **uploaded by the browser directly** -
they never pass through our API. Three steps:

```ts
// 1. Ask us to authorise one upload
const ticket = await api.post(`/api/agency/houses/${houseId}/images/upload-ticket`);
// -> { uploadUrl, publicId, params: { api_key, timestamp, signature }, expiresAt }

// 2. Send the file straight to Cloudinary
const form = new FormData();
form.append("file", file);
Object.entries(ticket.params).forEach(([k, v]) => form.append(k, v));
const uploaded = await fetch(ticket.uploadUrl, { method: "POST", body: form })
  .then(r => r.json());

// 3. Tell us what landed
await api.post(`/api/agency/houses/${houseId}/images`, {
  publicId: uploaded.public_id,
  width: uploaded.width,
  height: uploaded.height,
  format: uploaded.format,
  bytes: uploaded.bytes,
  cover: false,
});
```

Step 3 checks that the public id sits under the path the signature was issued for, so
send back what Cloudinary returned rather than constructing it. Anything else is a `403
FOREIGN_ASSET`.

The ticket expires after ten minutes. If a user picks a file and wanders off, request a
new one rather than reusing it.

Responses give you finished URLs - `url` and `thumbnailUrl` on an image, `coverImageUrl`
on a marketplace listing. They already carry the right transformation (`f_auto` serves
AVIF or WebP where supported), so use them as-is rather than building URLs from the
public id. **`coverImageUrl` is `null` when a house has no photograph** - that is the
signal to show a placeholder, and it is a real case rather than an error.

Uploads answer `503 IMAGES_NOT_CONFIGURED` when the backend has no Cloudinary account
configured. Hide the upload control rather than surfacing it.

---

## Things that will surprise you

**A house disappears from the marketplace when it is let.** `GET
/api/marketplace/listings/{id}` starts returning `404` because the listing projection
only contains houses that are both available and published. Handle it as "no longer
available" rather than an error.

**Invoices appear on their own.** There is no "create invoice" endpoint. A scheduled job
derives them from the lease's payment terms, roughly a month ahead. A freshly signed
lease has no invoices for a minute or so in dev.

**Paying is two steps.** `POST /api/renter/invoices/{id}/payments` returns a payment with
status `PENDING`, not `SETTLED` - a mobile money push has been started, not completed.
The invoice is unchanged until the provider confirms. Show "awaiting confirmation", and
do not mark the rent paid on a 201.

**Landlords read from two places.** Their tenancies come from `/api/landlord/leases` and
their money from `/api/landlord/earnings`. Different services behind the same gateway;
a landlord dashboard will call both.

**Commission may be zero.** Whether the platform takes a cut is configured per agency,
so `commissionAmount` is legitimately `0.00` and `netAmount` equals the gross. Do not
render that as an error or hide the row.

---

## Getting started quickly

The Postman collection in `postman/` documents all 57 endpoints with request bodies and
notes on what each one refuses. Import it with `gateway.postman_environment.json` and
click through a flow before writing any code - it is faster than reading this file, and
the folder descriptions explain the reasoning behind each rule.

For the exact schema of any request or response:

```
http://localhost:8081/q/openapi     # OpenAPI 3 document
http://localhost:8081/q/swagger-ui  # browsable, dev only
```

One per service - 8081 property, 8082 lease, 8083 payment, 8084 notification, 8085
agency. These are the only time you should use the direct ports.
