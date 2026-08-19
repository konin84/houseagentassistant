package com.digitalpartner.houseagent.notification.api.dto;

import com.digitalpartner.houseagent.notification.domain.Contact;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ContactDtos {

    /**
     * Every field but the address is optional, so a landlord can change one preference
     * without restating the rest.
     */
    public record UpsertContactRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 200) String displayName,
            /** BCP-47, e.g. "fr" or "en". Defaults to French when absent. */
            @Size(max = 16) String locale,
            Boolean notifyOnPayment,
            Boolean notifyOnArrears) {
    }

    /** Preferences only. Lets someone mute arrears mail without resending their address. */
    public record UpdatePreferencesRequest(
            Boolean notifyOnPayment,
            Boolean notifyOnArrears) {
    }

    public record ContactResponse(
            UUID partyId,
            String email,
            String displayName,
            String locale,
            boolean notifyOnPayment,
            boolean notifyOnArrears,
            Instant updatedAt) {

        public static ContactResponse from(Contact contact) {
            return new ContactResponse(
                    contact.partyId,
                    contact.email,
                    contact.displayName,
                    contact.locale,
                    contact.notifyOnPayment,
                    contact.notifyOnArrears,
                    contact.updatedAt);
        }
    }

    private ContactDtos() {
    }
}
