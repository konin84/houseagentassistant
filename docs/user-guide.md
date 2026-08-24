# User guide

What the platform does, told as the story it actually runs: an agency lists a house, a
renter finds it, a lease is signed, rent is invoiced and paid, and the landlord is told.

Every step below has been run end to end and shows the real response. Follow it in
Postman and you will see the same thing, which also makes this a decent smoke test.

There is no user interface yet. Where this says "the agent does X", it means an API call
that a screen will eventually make.

---

## The people

| Who | Role | Sees |
|---|---|---|
| **Agent** | `AGENT` | their agency's houses, leases and invoices - nothing of any other agency |
| **Agency admin** | `AGENCY_ADMIN` | the same, plus deleting houses and setting commission |
| **Landlord** | `LANDLORD` | their own properties' tenancies and earnings, across every agency they use |
| **Renter** | `RENTER` | their own invoices, and can pay them |
| **Anyone** | none | the public marketplace |

An agent belongs to exactly one agency. Landlords and renters belong to none: a landlord
may place one house with agency A and another with agency B, and sees both in one list.

---

## Before you start

```bash
docker compose up -d keycloak gateway redpanda mailpit
```

Then each service, from its own folder. Quarkus binds debugger port 5005, so the others
need their own:

```bash
cd property-service     && ../mvnw quarkus:dev
cd lease-service        && ../mvnw quarkus:dev -Ddebug=5006
cd payment-service      && ../mvnw quarkus:dev -Ddebug=5007
cd notification-service && ../mvnw quarkus:dev -Ddebug=5008
```

Import the Postman collection with the **gateway** environment, and run
**Authentication → Token - agent-a**. Everything below is one request per step.

`redpanda` matters more than it looks. Services tell each other things by publishing
events rather than calling each other, so without it a house never leaves the
marketplace and no invoice is ever raised.

---

## 1. The landlord says where to reach them

```
PUT /api/me/contact          (as landlord-one)
{ "email": "landlord@example.ci", "displayName": "M. Konan", "locale": "fr" }
```

Do this first or the landlord hears nothing later. An agency admin can also enter it on
a landlord's behalf when taking them on, with `PUT /api/contacts/{partyId}`.

`locale` decides the language of every notification and defaults to French.

---

## 2. The agency decides how money flows

```
PUT /api/agency/settlement-config     (as admin-a, AGENCY_ADMIN only)
{ "mode": "PLATFORM_COLLECTS", "commissionBps": 750 }
```

Two models, chosen per agency:

| Mode | What happens |
|---|---|
| `PLATFORM_COLLECTS` | The renter pays the agency, which keeps a commission and owes the landlord the rest. Every settled payment creates a payout. |
| `DIRECT_TO_LANDLORD` | The renter pays the landlord; the platform only records it. No commission, no payout. |

`commissionBps` is basis points, so `750` is 7.5%. An agency that never sets this behaves
as `DIRECT_TO_LANDLORD` - the platform holding no money is the safe assumption.

An `AGENT` gets `403` here. It is a commercial term, and an agent who could raise the
commission could change what every landlord on the books is paid.

---

## 3. The agent lists a house

```
POST /api/agency/houses               (as agent-a)
{ "landlordId": "...", "title": "Villa Cocody",
  "address": { "street": "Rue des Jardins", "district": "Cocody",
               "city": "Abidjan", "countryCode": "CI" },
  "bedrooms": 3, "bathrooms": 2,
  "pricePerMonth": "150000.00", "currency": "XOF" }
```

The house starts **available but unadvertised**. Nothing appears publicly until the
agent says so, which is deliberate: a house may be photographed one day and advertised
the next.

Note there is no `agencyId` in that request, and no endpoint anywhere takes one. It
comes from who you are, so there is nothing for a caller to tamper with.

### Photographs

```
POST /api/agency/houses/{id}/images/upload-ticket    -> a signed, one-off permission
     (browser uploads the file straight to Cloudinary)
POST /api/agency/houses/{id}/images                  -> register what landed
```

The image never passes through the platform. The first photograph registered becomes the
cover automatically, because a house with pictures and no cover shows a blank card.

Answers `503` until a Cloudinary account is configured, and everything else still works.

---

## 4. Advertising it

```
POST /api/agency/houses/{id}/publication
```

Now it is public. Anyone, with no login at all:

```
GET /api/marketplace/listings?city=Abidjan
```

A house reaches the marketplace only if it is **both available and advertised**. That is
why the marketplace never shows a let house: not because every search filters it out,
but because the list it searches only ever contains houses that qualify.

Withdraw at any time with `DELETE .../publication`. That takes it off the market without
pretending it is occupied.

---

## 5. Signing a lease

```
POST /api/agency/leases               (as agent-a)
{ "houseId": "...", "renterId": "...", "renterName": "Ama Kouassi",
  "landlordId": "...", "houseReference": "Villa Cocody",
  "rentAmount": "150000.00", "currency": "XOF",
  "cadence": "MONTHLY", "dueDayOfMonth": 5,
  "startDate": "2026-08-01", "endDate": "2027-08-28" }
```

The rent and terms live on the **lease**, not the house. Two renters in identical houses
can be on different terms, and the advertised price is not necessarily what was agreed.

`dueDayOfMonth` is 1-28 only. A lease due on the 31st has no due date in February, and
every scheme for handling that afterwards is worse than not allowing it.

### The same house cannot be let twice

Try signing again for an overlapping period and you get:

```
409  { "code": "HOUSE_ALREADY_LET" }
```

Including from a different agency. Two agencies letting the same physical house at once
is the worst version of the mistake, not an exception to it - and it is exactly the case
that per-agency checks would miss, since neither can see the other's leases.

This is enforced by the database rather than by application code, so it holds even when
two agents press the button in the same instant. One wins; the other gets the 409.

### The house leaves the market by itself

Within a second or two, the marketplace listing is gone. Nobody marked it unavailable -
lease-service announced the signing, and property-service acted on it. Availability is
*derived from whether a lease exists*, not typed in by an agent.

`GET /api/marketplace/listings/{id}` now answers `404`. That means "no longer
available", not an error.

Then `POST .../activation` when the renter moves in, which moves the house from reserved
to occupied. Rent has been owed since signing either way.

---

## 6. Rent is invoiced automatically

There is no "create invoice" endpoint. A job derives the whole schedule from the lease's
terms and raises whatever is missing, about a month ahead. In dev it runs every minute.

Wait a moment, then as the renter:

```
GET /api/renter/invoices        -> 3 invoices
GET /api/renter/balance         -> 450000.00 XOF
```

Three because the job raises everything due within the next 45 days. Running it again
changes nothing - it re-derives the same schedule and inserts only what is absent, so a
missed run repairs itself and a double run cannot double-bill.

The agency sees the same invoices under `/api/agency/invoices`, and its chase list -
everything past its due date and unpaid - under `/api/agency/invoices/overdue`.

---

## 7. The renter pays

```
POST /api/renter/invoices/{id}/payments
{ "amount": "150000.00", "method": "MOBILE_MONEY",
  "providerReference": "WAVE-UG-0001" }

-> 201  { "status": "PENDING" }
```

**`PENDING`, not paid.** A mobile money push has been sent to a handset; the money has
not moved. The invoice is untouched and still owed. Do not tell anyone the rent is paid
at this point.

When the provider confirms:

```
POST /api/agency/payments/{id}/settlement
{ "providerReference": "WAVE-UG-0001" }
```

*Now* the invoice is paid. Cash over the counter skips the wait - `POST
/api/agency/payments` with `"method": "CASH"` records money the agency already holds and
settles immediately.

### Sending the same payment twice

Providers retry their callbacks; that is normal, not a fault. So:

- The same `providerReference` twice is `409 PAYMENT_ALREADY_RECORDED`, and **the invoice
  is left exactly as it was**.
- Confirming an already-settled payment returns that payment, unchanged. A retry gets
  "yes, that one" rather than an error it will keep retrying against.
- Paying more than is outstanding is refused.

`providerReference` is required for everything except cash, because it is what makes all
of that possible.

---

## 8. The landlord is told

An email arrives without anyone sending one:

```
Subject: Loyer reçu - Villa Cocody

Bonjour M. Konan,

Le loyer de Ama Kouassi a été réglé.

Bien           : Villa Cocody
Locataire      : Ama Kouassi
Période        : du 2026-09-05 au 2026-10-05
Montant reçu   : 150000.00 XOF
Commission     : 11250.00 XOF
Net pour vous  : 138750.00 XOF
```

Read it at **http://localhost:8025** (Mailpit), which catches every outbound message so
nobody real is emailed during development.

The landlord can check the arithmetic themselves:

```
GET /api/landlord/earnings        gross 150000.00, commission 11250.00, net 138750.00
GET /api/landlord/earnings/total  everything received to date, net
```

Gross, commission and net are stored per payment at the rate that applied then, so
changing the commission later cannot rewrite what someone was already paid.

Landlords who would rather not hear about every payment can mute it:

```
PATCH /api/me/contact/preferences
{ "notifyOnPayment": false, "notifyOnArrears": true }
```

---

## 9. The agency owes the landlord

Under `PLATFORM_COLLECTS`, each settled payment creates a payout:

```
GET  /api/agency/payouts                       -> PENDING, net 138750.00
POST /api/agency/payouts/{id}/settlement       -> mark it remitted
```

That records that a transfer happened elsewhere. It does not move money - actually
paying out is a bank or mobile money disbursement.

Under `DIRECT_TO_LANDLORD` there are no payouts at all. The absence of a row is the
record that nothing is owed onward.

---

## 10. When rent does not arrive

A sweep notices invoices past their due date and announces each one **once**, however
often it runs. Both parties hear about it: the renter, who can fix it, and the landlord,
whose money is late.

The agency's own list is `GET /api/agency/invoices/overdue`, which is computed from the
due date and today rather than from a stored flag - so it is right even if no sweep has
ever run.

---

## 11. Ending a lease

```
POST /api/agency/leases/{id}/termination
{ "reason": "Tenancy completed" }
```

Three things follow on their own: the house returns to the marketplace if it was
advertised before, billing stops, and invoices for periods that will now never happen
are cancelled. One already part-paid, or covering time the renter lived through, stays
owed - moving out early does not refund rent already due.

Ending before move-in is recorded as a cancellation rather than a completed tenancy. The
effect on the house is the same.

---

## What each person cannot see

Worth trying, because refusals are most of the design:

| Try | Result |
|---|---|
| `agent-b` reading agency-a's house | `404` - not `403`. A foreign house and a missing one are indistinguishable, because a 403 would confirm it exists |
| A landlord opening another landlord's lease | `404` |
| A renter opening another renter's invoice | `404` - and they cannot pay it either |
| An `AGENT` deleting a house | `403` - needs `AGENCY_ADMIN` |
| `platform-admin` on any agency endpoint | `403` - a real role, but no agency, and a role alone is not enough |

The last is the clearest statement of how this works. Being authenticated says who you
are; it does not say whose data you may read. That comes from a claim in the token, and
a user without one sees nothing regardless of their role.

---

## Where to look when something seems wrong

| Symptom | Usually |
|---|---|
| Marketplace is empty | The house is not advertised, or has been let |
| No invoices after signing | `redpanda` is not running, or the generator has not run yet |
| Landlord got no email | No contact registered, or they muted it |
| Everything answers `401` | Token expired - fetch a new one |
| Everything answers `403` | Right role, wrong user - `platform-admin` has no claims |
| Image upload answers `503` | No Cloudinary account configured |

Useful windows: **http://localhost:8025** for mail, **http://localhost:8001** for what
the gateway is routing, **http://localhost:8180** for Keycloak (`admin` / `admin`).
