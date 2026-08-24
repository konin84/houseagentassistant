"""Generates the Postman collection and environment from one description of the API.

Kept as a script rather than a hand-edited 2,000-line JSON file: the collection has to
stay in step with the endpoints, and a diff on this file is readable in a way a diff on
generated JSON is not.

    python postman/build_collection.py
"""

import json
import pathlib

PROP = "{{propertyUrl}}"
LEASE = "{{leaseUrl}}"
PAY = "{{paymentUrl}}"
NOTIF = "{{notificationUrl}}"

# Health and OpenAPI always bypass the gateway. A health check exists to say
# whether one instance is up, and asking a load balancer produces the least useful
# possible answer to that question.
PROP_DIRECT = "{{propertyDirect}}"
LEASE_DIRECT = "{{leaseDirect}}"
PAY_DIRECT = "{{paymentDirect}}"
NOTIF_DIRECT = "{{notificationDirect}}"

AGENT = "AGENT"
ADMIN = "AGENCY_ADMIN"
LANDLORD = "LANDLORD"
RENTER = "RENTER"

DEV_HEADER_NOTE = ("Dev only. A production build does not contain the augmentor that "
                   "reads this, so it is ignored there.")


def url(base, path, query=None):
    raw = base + "/" + "/".join(path)
    u = {"raw": raw, "host": [base], "path": list(path)}
    if query:
        u["query"] = [{"key": k, "value": v} for k, v in query]
        u["raw"] = raw + "?" + "&".join("{}={}".format(k, v) for k, v in query)
    return u


def headers(role, party, agency, json_body):
    h = []
    if json_body:
        h.append({"key": "Content-Type", "value": "application/json"})
    if role:
        h.append({"key": "X-Dev-Roles", "value": role, "description": DEV_HEADER_NOTE})
    if role and agency:
        h.append({"key": "X-Dev-Agency", "value": "{{agencyId}}", "description": DEV_HEADER_NOTE})
    if party:
        h.append({"key": "X-Dev-Party", "value": party, "description": DEV_HEADER_NOTE})
    return h


def req(name, method, u, desc, role=None, party=None, agency=True, body=None, capture=None):
    item = {
        "name": name,
        "request": {
            "method": method,
            "header": headers(role, party, agency, body is not None),
            "url": u,
            "description": desc,
        },
    }
    if body is not None:
        item["request"]["body"] = {
            "mode": "raw",
            "raw": json.dumps(body, indent=2),
            "options": {"raw": {"language": "json"}},
        }
    if capture:
        var, expr = capture
        item["event"] = [{
            "listen": "test",
            "script": {
                "type": "text/javascript",
                "exec": [
                    "// Saves an id so the next request in this folder just works.",
                    "if (pm.response.code < 300) {",
                    "    const body = pm.response.json();",
                    "    const value = " + expr + ";",
                    "    if (value) {",
                    "        pm.collectionVariables.set('" + var + "', value);",
                    "        console.log('" + var + " =', value);",
                    "    }",
                    "}",
                ],
            },
        }]
    return item


def folder(name, desc, items):
    return {"name": name, "description": desc, "item": items}


def token_request(name, username, desc):
    """A password-grant token request that stores the result in {{accessToken}}.

    Password grant rather than the browser flow on purpose: there is no user agent in
    the loop here, and a redirect is something an API client cannot follow. Keycloak
    calls this Direct Access Grants, and it is enabled on the houseagent-backend client.
    """
    return {
        "name": name,
        "request": {
            "auth": {"type": "noauth"},
            "method": "POST",
            "header": [{"key": "Content-Type", "value": "application/x-www-form-urlencoded"}],
            "body": {
                "mode": "urlencoded",
                "urlencoded": [
                    {"key": "grant_type", "value": "password"},
                    {"key": "client_id", "value": "{{keycloakClientId}}"},
                    {"key": "client_secret", "value": "{{keycloakClientSecret}}"},
                    {"key": "username", "value": username},
                    {"key": "password", "value": "{{keycloakPassword}}"},
                ],
            },
            "url": {
                "raw": "{{keycloakUrl}}/realms/houseagent/protocol/openid-connect/token",
                "host": ["{{keycloakUrl}}"],
                "path": ["realms", "houseagent", "protocol", "openid-connect", "token"],
            },
            "description": desc,
        },
        "event": [{
            "listen": "test",
            "script": {
                "type": "text/javascript",
                "exec": [
                    "// Stores the token so every other request in the collection uses it.",
                    "if (pm.response.code === 200) {",
                    "    const body = pm.response.json();",
                    "    pm.collectionVariables.set('accessToken', body.access_token);",
                    "",
                    "    // Print the two claims the platform authorises on, because a",
                    "    // token that is valid but missing them fails in a way that looks",
                    "    // like a permissions bug rather than a realm misconfiguration.",
                    "    const claims = JSON.parse(",
                    "        Buffer.from(body.access_token.split('.')[1], 'base64').toString());",
                    "    console.log('roles     ', (claims.realm_access || {}).roles);",
                    "    console.log('agency_id ', claims.agency_id);",
                    "    console.log('party_id  ', claims.party_id);",
                    "} else {",
                    "    console.log('Token request failed. Is Keycloak running?',",
                    "                pm.response.code, pm.response.text());",
                    "}",
                ],
            },
        }],
    }


auth = folder(
    "Authentication - get a token",
    "Run **one** of these first - they are alternatives, not a sequence. Each stores "
    "its token in `{{accessToken}}`, which every other request in the collection sends "
    "as a bearer token, so switching who you are is one click.\n\n"
    "Running the whole folder leaves you holding the last token, `platform-admin`, "
    "which deliberately has neither claim - so the agency and personal folders will "
    "then answer 403. Start with **agent-a** unless you want something else.\n\n"
    "Needs Keycloak: `docker compose up -d keycloak`. The realm, roles, claim mappers "
    "and these six users are imported automatically from `infra/keycloak`.\n\n"
    "Every user's password is `password` - this realm is for development and says so.\n\n"
    "The test script prints `roles`, `agency_id` and `party_id` to the Postman console. "
    "Worth glancing at: a token that is valid but missing those claims produces 403s "
    "that look like a permissions bug rather than a realm problem.\n\n"
    "Without Keycloak you can still use the `X-Dev-*` headers already on every request "
    "- but only while `{{accessToken}}` is empty, because a real token always wins.",
    [
        token_request("Token - agent-a (AGENT, agency-a)", "agent-a",
                      "The everyday agency user. agency_id=agency-a."),
        token_request("Token - admin-a (AGENCY_ADMIN, agency-a)", "admin-a",
                      "Needed for deleting a house and for setting the settlement "
                      "policy - an AGENT gets 403 on both."),
        token_request("Token - agent-b (AGENT, agency-b)", "agent-b",
                      "A second agency. Get this token, then re-run Get house from the "
                      "property folder: 404, because it belongs to agency-a. That is "
                      "tenant isolation with a real token rather than an edited header."),
        token_request("Token - landlord-one (LANDLORD)", "landlord-one",
                      "party_id 11111111-1111-1111-1111-111111111111, matching "
                      "{{landlordId}}, so the portfolio and earnings folders return the "
                      "leases created by the agency folders."),
        token_request("Token - renter-one (RENTER)", "renter-one",
                      "party_id 22222222-2222-2222-2222-222222222222, matching "
                      "{{renterId}}."),
        token_request("Token - platform-admin (PLATFORM_ADMIN)", "platform-admin",
                      "Carries no agency_id and no party_id, so agency and personal "
                      "endpoints both refuse it. Useful for checking that a role alone "
                      "is not enough."),
        {
            "name": "Clear token (back to X-Dev-* headers)",
            "request": {
                "auth": {"type": "noauth"},
                "method": "GET",
                "header": [],
                "url": {"raw": "{{keycloakUrl}}/realms/houseagent/.well-known/openid-configuration",
                        "host": ["{{keycloakUrl}}"],
                        "path": ["realms", "houseagent", ".well-known",
                                 "openid-configuration"]},
                "description": "Empties {{accessToken}} so the collection falls back to "
                               "the dev headers. Also a quick check that the realm "
                               "imported: it should list the token endpoint.",
            },
            "event": [{
                "listen": "test",
                "script": {"type": "text/javascript",
                           "exec": ["pm.collectionVariables.set('accessToken', '');",
                                    "console.log('accessToken cleared');"]},
            }],
        },
    ])


# --------------------------------------------------------------- property-service

houses = folder(
    "Agency - houses",
    "Scoped to the caller's agency by the @TenantId filter. No endpoint takes an "
    "agencyId - it comes from the identity, so there is nothing for a caller to tamper "
    "with. Another agency's house answers 404, indistinguishable from one that never "
    "existed: a 403 would confirm it is there.",
    [
        req("Create house", "POST", url(PROP, ["api", "agency", "houses"]),
            "Starts AVAILABLE and unpublished. Saves the new id into {{houseId}}, which "
            "the rest of this collection uses.",
            role=AGENT, capture=("houseId", "body.id"),
            body={
                "landlordId": "{{landlordId}}",
                "title": "Villa Cocody",
                "description": "Bright three-bedroom villa with a courtyard.",
                "address": {"street": "Rue des Jardins", "district": "Cocody",
                            "city": "Abidjan", "countryCode": "CI"},
                "bedrooms": 3, "bathrooms": 2, "sizeSqm": 180,
                "pricePerMonth": "150000.00", "currency": "XOF",
            }),
        req("List houses", "GET",
            url(PROP, ["api", "agency", "houses"], [("page", "0"), ("size", "20")]),
            "Only this agency's houses. Size is capped at 100.", role=AGENT),
        req("Get house", "GET", url(PROP, ["api", "agency", "houses", "{{houseId}}"]),
            "To see isolation work, change X-Dev-Agency to {{otherAgencyId}} and watch "
            "this become a 404.", role=AGENT),
        req("Update house", "PATCH", url(PROP, ["api", "agency", "houses", "{{houseId}}"]),
            "Partial: every field is optional and absent means leave unchanged.",
            role=AGENT,
            body={"pricePerMonth": "165000.00", "description": "Newly repainted."}),
        req("Publish to marketplace", "POST",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "publication"]),
            "Advertises the house. It only reaches the marketplace if it is also "
            "AVAILABLE - both must be true.", role=AGENT),
    ])

property_teardown = folder(
    "Teardown - withdraw and delete",
    "Kept in a folder of its own, and last, so that running the service folder from top "
    "to bottom works: withdrawing or deleting the house first would leave every request "
    "below it looking at something that is no longer there.",
    [
        req("Withdraw from marketplace", "DELETE",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "publication"]),
            "Takes it off the marketplace without changing availability. Run **Get "
            "listing** again afterwards to watch it 404.", role=AGENT),
        req("Delete house", "DELETE", url(PROP, ["api", "agency", "houses", "{{houseId}}"]),
            "AGENCY_ADMIN only. Change X-Dev-Roles to AGENT to get a 403.", role=ADMIN),
    ])

images = folder(
    "Agency - images (Cloudinary)",
    "Three steps: sign an upload here, send the file to Cloudinary from the browser, "
    "then register the public id. The bytes never pass through this service.\n\n"
    "Needs CLOUDINARY_* in .env. Without it the ticket endpoint answers 503 "
    "IMAGES_NOT_CONFIGURED and everything else still works.",
    [
        req("Request upload ticket", "POST",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "images", "upload-ticket"]),
            "Returns a signature bound to one path under this agency and house, plus the "
            "form fields to post to Cloudinary. Saves {{publicId}}.",
            role=AGENT, capture=("publicId", "body.publicId")),
        req("Register uploaded image", "POST",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "images"]),
            "Step 3, and the security check: the public id must sit under the path it "
            "was signed for, or this is 403 FOREIGN_ASSET. The first image registered "
            "becomes the cover whatever you pass.",
            role=AGENT, capture=("imageId", "body.id"),
            body={"publicId": "{{publicId}}", "width": 1600, "height": 1200,
                  "format": "jpg", "bytes": 482913, "cover": False}),
        req("List images", "GET",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "images"]),
            "URLs are rendered from the stored public id per request, never stored - so "
            "changing a crop is a code change rather than a migration.", role=AGENT),
        req("Set cover", "PUT",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "images", "{{imageId}}", "cover"]),
            "Demotes whichever image held it. At most one cover per house is a partial "
            "unique index, not application logic.", role=AGENT),
        req("Delete image", "DELETE",
            url(PROP, ["api", "agency", "houses", "{{houseId}}", "images", "{{imageId}}"]),
            "Destroys the Cloudinary asset once the change commits. If it was the cover, "
            "the next image is promoted rather than leaving the listing without one.",
            role=AGENT),
    ])

marketplace = folder(
    "Public marketplace (anonymous)",
    "No identity at all - these requests send no X-Dev-* headers. Cross-agency on "
    "purpose, and the projection behind it holds only houses that are both AVAILABLE "
    "and published, so no query here needs an availability filter.",
    [
        req("Search listings", "GET",
            url(PROP, ["api", "marketplace", "listings"],
                [("city", "Abidjan"), ("country", "CI"), ("minPrice", "50000"),
                 ("maxPrice", "500000"), ("minBedrooms", "2"), ("page", "0"), ("size", "20")]),
            "Every parameter is optional - delete the ones you do not want. Size is "
            "capped at 100."),
        req("Get listing", "GET", url(PROP, ["api", "marketplace", "listings", "{{houseId}}"]),
            "404 once the house is let or withdrawn: the projection simply stops holding "
            "it."),
    ])

# ------------------------------------------------------------------ lease-service

leases = folder(
    "Agency - leases",
    "Signing is guarded by a PostgreSQL exclusion constraint rather than by application "
    "logic, so two agents signing the same house at the same instant cannot both win. "
    "The loser gets 409 HOUSE_ALREADY_LET - including across agencies.",
    [
        req("Sign lease", "POST", url(LEASE, ["api", "agency", "leases"]),
            "Send this twice to see the constraint work. Saves {{leaseId}}.",
            role=AGENT, capture=("leaseId", "body.id"),
            body={
                "houseId": "{{houseId}}", "renterId": "{{renterId}}",
                "renterName": "Ama Kouassi", "renterPhone": "+225 07 00 00 00",
                "landlordId": "{{landlordId}}", "houseReference": "Villa Cocody 12",
                "rentAmount": "150000.00", "currency": "XOF", "cadence": "MONTHLY",
                "dueDayOfMonth": 5, "depositAmount": "300000.00",
                "startDate": "2026-09-01", "endDate": "2027-08-31",
            }),
        req("List leases", "GET",
            url(LEASE, ["api", "agency", "leases"], [("page", "0"), ("size", "20")]),
            "This agency's leases only.", role=AGENT),
        req("Get lease", "GET", url(LEASE, ["api", "agency", "leases", "{{leaseId}}"]),
            "Includes firstDueDate, derived from the start date and the agreed due day.",
            role=AGENT),
        req("Record move-in", "POST",
            url(LEASE, ["api", "agency", "leases", "{{leaseId}}", "activation"]),
            "PENDING_MOVE_IN to ACTIVE. The house was already unavailable before this - "
            "signing is what took it off the market.", role=AGENT),
    ])

lease_teardown = folder(
    "Teardown - terminate",
    "Last, so the landlord folder above still has a current tenancy to show. Ending the "
    "lease first would leave 'My leases' correctly empty and look like a bug.",
    [
        req("Terminate lease", "POST",
            url(LEASE, ["api", "agency", "leases", "{{leaseId}}", "termination"]),
            "Frees the house. Before move-in this cancels, after it this ends - either "
            "way one LeaseEnded event goes out, property-service relists the house and "
            "payment-service stops billing it.",
            role=AGENT, body={"reason": "Tenancy completed"}),
    ])

landlord_leases = folder(
    "Landlord - portfolio",
    "No landlordId parameter anywhere: it comes from party_id. landlord_lease_view has "
    "no tenant filter, so that predicate is the entire access control - and a "
    "landlord's portfolio deliberately spans every agency they work with.",
    [
        req("My leases", "GET",
            url(LEASE, ["api", "landlord", "leases"],
                [("currentOnly", "true"), ("page", "0"), ("size", "20")]),
            "currentOnly=false for the full history including ended tenancies.",
            role=LANDLORD, party="{{landlordId}}", agency=False),
        req("My lease", "GET", url(LEASE, ["api", "landlord", "leases", "{{leaseId}}"]),
            "Another landlord's lease is 404, not 403 - a 403 would confirm it exists.",
            role=LANDLORD, party="{{landlordId}}", agency=False),
    ])

# ---------------------------------------------------------------- payment-service

settlement = folder(
    "Agency - settlement policy",
    "Whether the platform holds the money is decided per agency. An agency that has "
    "never configured one behaves as DIRECT_TO_LANDLORD, because the mode where the "
    "platform holds nothing is the safe direction to be wrong in.",
    [
        req("Get settlement config", "GET", url(PAY, ["api", "agency", "settlement-config"]),
            "Returns defaults if none was set; reading never creates a row.", role=AGENT),
        req("Set settlement config", "PUT", url(PAY, ["api", "agency", "settlement-config"]),
            "AGENCY_ADMIN only - an agent who could raise the commission could change "
            "what every landlord on the books is paid. Commission is basis points, so "
            "750 is 7.5%, and must be 0 unless the mode is PLATFORM_COLLECTS.",
            role=ADMIN, body={"mode": "PLATFORM_COLLECTS", "commissionBps": 750}),
    ])

invoices = folder(
    "Agency - invoices",
    "Invoices are derived from the lease's payment modality by a scheduled job, not "
    "created by hand - which is why there is no POST here. In dev the generator runs "
    "every minute.",
    [
        req("List invoices", "GET",
            url(PAY, ["api", "agency", "invoices"],
                [("unpaidOnly", "false"), ("page", "0"), ("size", "20")]),
            "Saves the first invoice id into {{invoiceId}}.",
            role=AGENT,
            capture=("invoiceId", "body.items && body.items.length ? body.items[0].id : null")),
        req("Overdue invoices", "GET",
            url(PAY, ["api", "agency", "invoices", "overdue"], [("page", "0"), ("size", "20")]),
            "The chase list. Lateness is derived from the due date and the clock, so "
            "this is right even if the arrears sweep has never run.", role=AGENT),
        req("Get invoice", "GET", url(PAY, ["api", "agency", "invoices", "{{invoiceId}}"]),
            "outstanding and overdue are computed on read, not stored.", role=AGENT),
    ])

payments = folder(
    "Agency - payments",
    "Rent arrives two ways. Cash over the counter is recorded already settled; a mobile "
    "money push is initiated by the renter and confirmed here when the provider says so.",
    [
        req("Record a settled payment", "POST", url(PAY, ["api", "agency", "payments"]),
            "Money the agency already holds. providerReference is the idempotency key "
            "and is required for everything but CASH - reuse one and this is 409 "
            "PAYMENT_ALREADY_RECORDED, with the invoice left untouched. Paying more than "
            "the balance is also 409.",
            role=AGENT, capture=("paymentId", "body.id"),
            body={"invoiceId": "{{invoiceId}}", "amount": "150000.00", "method": "CASH"}),
        req("List payments", "GET",
            url(PAY, ["api", "agency", "payments"], [("page", "0"), ("size", "20")]),
            "This agency's payments, newest first.", role=AGENT),
        req("Get payment", "GET", url(PAY, ["api", "agency", "payments", "{{paymentId}}"]),
            "", role=AGENT),
        req("Confirm settlement", "POST",
            url(PAY, ["api", "agency", "payments", "{{paymentId}}", "settlement"]),
            "The provider callback. Deliberately safe to send twice: a redelivered "
            "confirmation returns the payment it already settled rather than crediting "
            "the invoice again.",
            role=AGENT, body={"providerReference": "WAVE-TX-0001"}),
        req("Mark payment failed", "POST",
            url(PAY, ["api", "agency", "payments", "{{paymentId}}", "failure"]),
            "The invoice stays owed. Note that run in order this answers 409: the "
            "payment above has already settled, and money does not un-move. To see it "
            "succeed, initiate one from the renter folder and fail that instead.",
            role=AGENT, body={"reason": "Insufficient funds"}),
    ])

payouts = folder(
    "Agency - payouts",
    "Only exist under PLATFORM_COLLECTS. Under DIRECT_TO_LANDLORD the absence of a row "
    "is the record that nothing is owed onward.",
    [
        req("List payouts", "GET",
            url(PAY, ["api", "agency", "payouts"],
                [("pendingOnly", "true"), ("page", "0"), ("size", "20")]),
            "Gross, commission and net are stored per payout at the rate that applied "
            "when it settled, so a later rate change cannot rewrite history.",
            role=AGENT,
            capture=("payoutId", "body.items && body.items.length ? body.items[0].id : null")),
        req("Mark payout settled", "POST",
            url(PAY, ["api", "agency", "payouts", "{{payoutId}}", "settlement"]),
            "Records that a transfer happened elsewhere; it does not move money.",
            role=AGENT),
    ])

renter = folder(
    "Renter - my rent",
    "The isolation rule with the sharpest edge: an invoice a renter can see is an "
    "invoice a renter can pay, so the predicate here guards money and not just data.",
    [
        req("My invoices", "GET",
            url(PAY, ["api", "renter", "invoices"],
                [("unpaidOnly", "true"), ("page", "0"), ("size", "20")]),
            "Across every agency this renter rents from. Saves {{renterInvoiceId}}.",
            role=RENTER, party="{{renterId}}", agency=False,
            capture=("renterInvoiceId",
                     "body.items && body.items.length ? body.items[0].invoiceId : null")),
        req("My invoice", "GET",
            url(PAY, ["api", "renter", "invoices", "{{renterInvoiceId}}"]),
            "Another renter's invoice is 404.",
            role=RENTER, party="{{renterId}}", agency=False),
        req("My balance", "GET", url(PAY, ["api", "renter", "balance"]),
            "One number: everything currently owed, so a client need not sum it.",
            role=RENTER, party="{{renterId}}", agency=False),
        req("Pay an invoice", "POST",
            url(PAY, ["api", "renter", "invoices", "{{renterInvoiceId}}", "payments"]),
            "Lands PENDING - initiating a mobile money push is not the money arriving. "
            "Confirm it from the agency folder to actually credit the invoice.",
            role=RENTER, party="{{renterId}}", agency=False,
            capture=("paymentId", "body.id"),
            body={"amount": "150000.00", "method": "MOBILE_MONEY",
                  "providerReference": "WAVE-TX-{{$randomInt}}"}),
    ])

earnings = folder(
    "Landlord - earnings",
    "What the landlord was actually paid, net of whatever commission applied at the "
    "time.",
    [
        req("My earnings", "GET",
            url(PAY, ["api", "landlord", "earnings"], [("page", "0"), ("size", "20")]),
            "Gross, commission and net on every row, so 'where did the rest go' is "
            "answered in the same response rather than in a support conversation.",
            role=LANDLORD, party="{{landlordId}}", agency=False),
        req("My total", "GET", url(PAY, ["api", "landlord", "earnings", "total"]),
            "Sums stored net amounts rather than reapplying today's rate.",
            role=LANDLORD, party="{{landlordId}}", agency=False),
    ])

# ----------------------------------------------------------- notification-service

me = folder(
    "My contact",
    "Where a landlord or renter's mail goes. This service has no agency scoping at all: "
    "one landlord working with three agencies is one person with one address who should "
    "get one email about one payment.",
    [
        req("My contact", "GET", url(NOTIF, ["api", "me", "contact"]),
            "404 until one is set.", role=LANDLORD, party="{{landlordId}}", agency=False),
        req("Set my contact", "PUT", url(NOTIF, ["api", "me", "contact"]),
            "locale is functional rather than decorative: it picks the language of every "
            "notification, and defaults to French.",
            role=LANDLORD, party="{{landlordId}}", agency=False,
            body={"email": "landlord@example.ci", "displayName": "M. Konan",
                  "locale": "fr", "notifyOnPayment": True, "notifyOnArrears": True}),
        req("Update my preferences", "PATCH",
            url(NOTIF, ["api", "me", "contact", "preferences"]),
            "Mute one kind of mail without restating an address. Opting out means the "
            "message is never composed, so nothing sits unsent in the delivery log.",
            role=LANDLORD, party="{{landlordId}}", agency=False,
            body={"notifyOnPayment": True, "notifyOnArrears": False}),
    ])

contacts = folder(
    "Agency admin - contacts",
    "An agency onboarding a landlord's address. AGENCY_ADMIN only, and interim: once "
    "Keycloak owns identity this table becomes a cache of it rather than the source.",
    [
        req("Upsert a contact", "PUT", url(NOTIF, ["api", "contacts", "{{landlordId}}"]),
            "Only the address is required; the rest keep their current values.",
            role=ADMIN,
            body={"email": "landlord@example.ci", "displayName": "M. Konan", "locale": "fr"}),
        req("Get a contact", "GET", url(NOTIF, ["api", "contacts", "{{landlordId}}"]),
            "", role=ADMIN),
    ])

SERVICES = [("property", PROP_DIRECT), ("lease", LEASE_DIRECT),
            ("payment", PAY_DIRECT), ("notification", NOTIF_DIRECT)]

health = folder(
    "Health and API docs",
    "Every service exposes the same three. Swagger UI is on in dev and switched off "
    "in production; the OpenAPI document is served in both.\n\n"
    "These go straight to the service ports even when the rest of the collection is "
    "pointed at the gateway - a health check that answers for whichever instance a "
    "load balancer picked is not telling you what you asked.",
    [req(n + " - health", "GET", url(b, ["q", "health"]), "", None) for n, b in SERVICES]
    + [req(n + " - OpenAPI", "GET", url(b, ["q", "openapi"]), "", None) for n, b in SERVICES]
    + [req(n + " - Swagger UI", "GET", url(b, ["q", "swagger-ui"]), "Dev only.", None)
       for n, b in SERVICES])

DESCRIPTION = """Every endpoint across the four services of houseagentassistant.

## Setting up

```
docker compose up -d keycloak gateway redpanda mailpit
```

Then each service from its own folder. Quarkus dev mode binds debugger port 5005, so
give the second and later ones their own:

```
cd property-service     && ../mvnw quarkus:dev
cd lease-service        && ../mvnw quarkus:dev -Ddebug=5006
cd payment-service      && ../mvnw quarkus:dev -Ddebug=5007
cd notification-service && ../mvnw quarkus:dev -Ddebug=5008
```

## Pick an environment

The collection is the same either way; only the base URLs differ.

| Environment | Talks to | Use when |
|---|---|---|
| `local-dev` | the four ports directly | debugging one service |
| `gateway` | `localhost:8000` | everything else, and what a frontend will use |

## Logging in

Run **one** request from the **Authentication** folder. It stores a token in
`{{accessToken}}`, which every other request sends as a bearer token, so changing who
you are is one click. Start with `agent-a`.

The realm has two clients. This collection uses `houseagent-backend`, which allows
password grant because there is no browser here to redirect. A frontend uses
`houseagent-web` instead - authorization code with PKCE, and password grant refused. See
`docs/frontend.md`.

### Without Keycloak

Every request also carries `X-Dev-Roles`, `X-Dev-Agency` and `X-Dev-Party`. They are
read by a bean that exists only in dev builds, and only when no real token is present -
so they let you poke at a service with no Docker at all, and stop mattering the moment
you fetch a token. Send neither and you are an anonymous visitor, which is how the
marketplace folder works.

## Suggested order

1. **property-service** - creates a house, leaves `{{houseId}}` set
2. **lease-service** - signs a lease against it
3. **payment-service** - invoices derived from that lease
4. **notification-service** - contact details; independent of the rest

Ids are captured into collection variables as you go, so a folder can be worked top to
bottom without copying anything. Watch the Postman console to see what was captured.

`docs/user-guide.md` walks the same path as a story, with what each person sees and why.

## Two folders need more than the service

- **payment-service** needs `redpanda`. It learns what to bill from lease events, so
  without a broker it never hears about a signed lease and every list is correctly
  empty. Invoices appear about a minute after signing, when the generator next runs.
- **Agency - images** needs `CLOUDINARY_*` in `.env`, or the upload ticket answers 503.

## Seeing the rules work

Most of the interesting behaviour is what these endpoints *refuse*:

- Get a token as `agent-b`, then re-run **Get house** - 404, not 403. Another agency's
  house and a house that does not exist are deliberately indistinguishable.
- Send **Sign lease** twice - the second is 409, refused by a database constraint rather
  than by application code.
- Send **Record a settled payment** twice with the same reference - 409, and the invoice
  is left untouched.
- Use the `agent-a` token on **Delete house** - 403, it needs AGENCY_ADMIN.
- Use the `platform-admin` token anywhere - 403 everywhere, because a role alone is not
  enough. The agency and personal endpoints need a claim it deliberately lacks.
"""

collection = {
    "info": {
        "name": "houseagentassistant",
        "description": DESCRIPTION,
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "auth": {"type": "bearer",
             "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]},
    "variable": [
        {"key": "propertyUrl", "value": "http://localhost:8081"},
        {"key": "leaseUrl", "value": "http://localhost:8082"},
        {"key": "paymentUrl", "value": "http://localhost:8083"},
        {"key": "notificationUrl", "value": "http://localhost:8084"},
        {"key": "propertyDirect", "value": "http://localhost:8081",
         "description": "Bypasses the gateway. Used by the health folder only."},
        {"key": "leaseDirect", "value": "http://localhost:8082"},
        {"key": "paymentDirect", "value": "http://localhost:8083"},
        {"key": "notificationDirect", "value": "http://localhost:8084"},
        {"key": "keycloakUrl", "value": "http://localhost:8180"},
        {"key": "keycloakClientId", "value": "houseagent-backend"},
        {"key": "keycloakClientSecret", "value": "houseagent-dev-secret",
         "description": "Matches the dev realm in infra/keycloak. Not a production secret."},
        {"key": "keycloakPassword", "value": "password",
         "description": "Every seeded user shares it. Development realm only."},
        {"key": "accessToken", "value": "",
         "description": "Filled in by the Authentication folder. While empty, the "
                        "X-Dev-* headers stand in."},
        {"key": "agencyId", "value": "agency-a"},
        {"key": "otherAgencyId", "value": "agency-b",
         "description": "Swap into X-Dev-Agency to watch isolation answer 404."},
        {"key": "landlordId", "value": "11111111-1111-1111-1111-111111111111"},
        {"key": "renterId", "value": "22222222-2222-2222-2222-222222222222"},
        {"key": "houseId", "value": "", "description": "Captured by Create house."},
        {"key": "imageId", "value": "", "description": "Captured by Register uploaded image."},
        {"key": "publicId", "value": "", "description": "Captured by Request upload ticket."},
        {"key": "leaseId", "value": "", "description": "Captured by Sign lease."},
        {"key": "invoiceId", "value": "", "description": "Captured by List invoices."},
        {"key": "renterInvoiceId", "value": "", "description": "Captured by My invoices."},
        {"key": "paymentId", "value": "", "description": "Captured by Record a settled payment."},
        {"key": "payoutId", "value": "", "description": "Captured by List payouts."},
    ],
    "item": [
        auth,
        folder("property-service (8081)",
               "Houses, their photographs, and the public marketplace. Runs top to "
               "bottom: the house created first is the one every later request uses.",
               [houses, images, marketplace, property_teardown]),
        folder("lease-service (8082)",
               "Leases, and a landlord's view of their own tenancies. Needs a house, so "
               "run the property-service folder first.",
               [leases, landlord_leases, lease_teardown]),
        folder("payment-service (8083)",
               "Invoices, payments, commission and landlord payouts.\n\n"
               "**This folder needs a broker.** payment-service learns what to bill from "
               "lease events, so without `docker compose up -d redpanda` it never hears "
               "about a signed lease, every list here is correctly empty, and the "
               "requests that need an invoice id have nothing to work with.\n\n"
               "With the broker running: sign a lease, wait for the invoice generator "
               "(every minute in dev), then start at **List invoices** - it captures the "
               "id the rest of the folder uses.",
               [settlement, invoices, payments, payouts, renter, earnings]),
        folder("notification-service (8084)",
               "Contact details and notification preferences.",
               [me, contacts]),
        health,
    ],
}

def env(name, property_url, lease_url, payment_url, notification_url):
    """The same collection, pointed either at the services or at the gateway."""
    return {
        "name": name,
        "values": [
            {"key": "propertyUrl", "value": property_url, "type": "default", "enabled": True},
            {"key": "leaseUrl", "value": lease_url, "type": "default", "enabled": True},
            {"key": "paymentUrl", "value": payment_url, "type": "default", "enabled": True},
            {"key": "notificationUrl", "value": notification_url, "type": "default", "enabled": True},
            # Always the real ports, whichever environment is selected.
            {"key": "propertyDirect", "value": "http://localhost:8081", "type": "default", "enabled": True},
            {"key": "leaseDirect", "value": "http://localhost:8082", "type": "default", "enabled": True},
            {"key": "paymentDirect", "value": "http://localhost:8083", "type": "default", "enabled": True},
            {"key": "notificationDirect", "value": "http://localhost:8084", "type": "default", "enabled": True},
            # Never the gateway: a token's `iss` is built from the host it was
            # requested through, and the services expect localhost:8180.
            {"key": "keycloakUrl", "value": "http://localhost:8180", "type": "default", "enabled": True},
            {"key": "keycloakClientId", "value": "houseagent-backend", "type": "default", "enabled": True},
            {"key": "keycloakClientSecret", "value": "houseagent-dev-secret", "type": "secret", "enabled": True},
            {"key": "keycloakPassword", "value": "password", "type": "secret", "enabled": True},
            {"key": "accessToken", "value": "", "type": "secret", "enabled": True},
            {"key": "agencyId", "value": "agency-a", "type": "default", "enabled": True},
            {"key": "otherAgencyId", "value": "agency-b", "type": "default", "enabled": True},
            {"key": "landlordId", "value": "11111111-1111-1111-1111-111111111111",
             "type": "default", "enabled": True},
            {"key": "renterId", "value": "22222222-2222-2222-2222-222222222222",
             "type": "default", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }


GATEWAY = "http://localhost:8000"

environments = {
    "local-dev.postman_environment.json": env(
        "houseagentassistant - direct to services",
        "http://localhost:8081", "http://localhost:8082",
        "http://localhost:8083", "http://localhost:8084"),
    "gateway.postman_environment.json": env(
        "houseagentassistant - through the gateway",
        GATEWAY, GATEWAY, GATEWAY, GATEWAY),
}



def count(items):
    total = 0
    for item in items:
        total += count(item["item"]) if "item" in item else 1
    return total


if __name__ == "__main__":
    out = pathlib.Path(__file__).parent
    (out / "houseagentassistant.postman_collection.json").write_text(
        json.dumps(collection, indent=2) + "\n", encoding="utf-8")
    for filename, document in environments.items():
        (out / filename).write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print("requests:", count(collection["item"]))
    print("environments:", ", ".join(environments))
