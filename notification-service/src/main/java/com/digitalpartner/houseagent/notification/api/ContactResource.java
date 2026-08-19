package com.digitalpartner.houseagent.notification.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.notification.api.dto.ContactDtos.ContactResponse;
import com.digitalpartner.houseagent.notification.api.dto.ContactDtos.UpdatePreferencesRequest;
import com.digitalpartner.houseagent.notification.api.dto.ContactDtos.UpsertContactRequest;
import com.digitalpartner.houseagent.notification.domain.Contact;
import com.digitalpartner.houseagent.notification.security.CallerContext;
import com.digitalpartner.houseagent.notification.service.ContactDirectory;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Where a landlord or renter's mail goes.
 *
 * <p>Two ways in, because two people legitimately need to set it. An agency onboards a
 * landlord and enters their address; the landlord can then correct it and choose what
 * they hear about.
 *
 * <p>That an agency can write another party's address is a real trust assumption, and
 * an interim one: notifications could be redirected by whoever holds an
 * {@code AGENCY_ADMIN} token. It is bounded deliberately - agents cannot, only agency
 * admins - and it disappears once Keycloak owns identity and this table becomes a cache
 * of it rather than the source.
 */
@Path("/api")
@Tag(name = "Contacts", description = "Delivery addresses and notification preferences")
@Produces(MediaType.APPLICATION_JSON)
public class ContactResource {

    @Inject
    ContactDirectory contacts;

    @Inject
    CallerContext caller;

    // ------------------------------------------------------------- own contact

    @GET
    @Path("/me/contact")
    @RolesAllowed({Roles.LANDLORD, Roles.RENTER})
    public ContactResponse myContact() {
        UUID partyId = caller.requirePartyId();
        return contacts.find(partyId)
                .map(ContactResponse::from)
                .orElseThrow(() -> new NotFoundException("No contact details for " + partyId));
    }

    /** A landlord or renter setting their own address. */
    @PUT
    @Path("/me/contact")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({Roles.LANDLORD, Roles.RENTER})
    public ContactResponse setMyContact(@Valid UpsertContactRequest request) {
        Contact saved = contacts.upsert(
                caller.requirePartyId(),
                request.email(),
                request.displayName(),
                request.locale(),
                request.notifyOnPayment(),
                request.notifyOnArrears());
        return ContactResponse.from(saved);
    }

    /** Muting one kind of mail without restating an address. */
    @PATCH
    @Path("/me/contact/preferences")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({Roles.LANDLORD, Roles.RENTER})
    public ContactResponse setMyPreferences(UpdatePreferencesRequest request) {
        Contact saved = contacts.upsert(
                caller.requirePartyId(), null, null, null,
                request == null ? null : request.notifyOnPayment(),
                request == null ? null : request.notifyOnArrears());
        return ContactResponse.from(saved);
    }

    // ----------------------------------------------------------- agency onboarding

    /**
     * An agency recording a landlord's address when it takes them on.
     *
     * <p>{@code AGENCY_ADMIN} only - see the note on this class about why this is a
     * bounded, interim capability rather than an ordinary one.
     */
    @PUT
    @Path("/contacts/{partyId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.AGENCY_ADMIN)
    public ContactResponse upsert(@PathParam("partyId") UUID partyId,
                                  @Valid UpsertContactRequest request) {
        Contact saved = contacts.upsert(
                partyId,
                request.email(),
                request.displayName(),
                request.locale(),
                request.notifyOnPayment(),
                request.notifyOnArrears());
        return ContactResponse.from(saved);
    }

    @GET
    @Path("/contacts/{partyId}")
    @RolesAllowed(Roles.AGENCY_ADMIN)
    public ContactResponse get(@PathParam("partyId") UUID partyId) {
        return contacts.find(partyId)
                .map(ContactResponse::from)
                .orElseThrow(() -> new NotFoundException("No contact details for " + partyId));
    }
}
