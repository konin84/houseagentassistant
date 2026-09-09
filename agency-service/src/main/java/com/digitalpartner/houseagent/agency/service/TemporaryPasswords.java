package com.digitalpartner.houseagent.agency.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * One-time passwords for accounts an agency creates on somebody's behalf.
 *
 * <p>Returned once, in the creation response, for the admin to pass on. Keycloak marks
 * it temporary, so it stops working the moment the real person logs in and chooses
 * their own - which bounds how long a credential an administrator has seen remains
 * usable.
 *
 * <p>The better answer is an email invitation, where nobody but the recipient ever
 * holds a working credential. That needs SMTP configured in Keycloak, so this is what
 * exists until then rather than what should exist forever.
 */
@ApplicationScoped
public class TemporaryPasswords {

    // SecureRandom rather than Random: these are credentials, briefly, and a
    // predictable sequence would let somebody guess the next account's password.
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
