package com.digitalpartner.houseagent.agency.service;

import com.digitalpartner.houseagent.agency.identity.PhoneNumbers;
import com.digitalpartner.houseagent.agency.identity.UserDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Normalises a phone number and checks nobody else is already using it.
 *
 * <p>One place, called by all four paths that create a person, because a number that is
 * normalised on one of them and not another is a person who can sign up but not sign in.
 * The four are far enough apart in the code that the rule would have drifted.
 */
@ApplicationScoped
public class PhoneIdentifiers {

    @Inject
    UserDirectory users;

    /**
     * The country a bare local number is assumed to belong to.
     *
     * <p>Configured rather than constant: an agency in Senegal typing a local number
     * should not silently acquire an Ivorian one. It is a deployment-wide setting, which
     * is the honest limit of this approach - a platform genuinely spanning countries
     * wants the number entered in full international form, and this default is what
     * makes the common case pleasant rather than what makes the rare case correct.
     */
    @ConfigProperty(name = "app.phone.default-calling-code", defaultValue = "225")
    String defaultCallingCode;

    /**
     * @return the normalised number, or null if none was given
     * @throws AlreadyRegisteredException if somebody else holds it
     */
    public String claim(String raw) {
        String normalized = PhoneNumbers.normalize(raw, defaultCallingCode);
        if (normalized == null) {
            return null;
        }
        users.findByPhone(normalized).ifPresent(existing -> {
            // Deliberately not "that number belongs to a landlord at another agency".
            // The same reticence as the email message: an error that describes who else
            // holds an identifier turns this endpoint into a lookup service.
            throw new AlreadyRegisteredException(
                    "That phone number already belongs to an account");
        });
        return normalized;
    }
}
