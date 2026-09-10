package com.digitalpartner.houseagent.agency.identity;

/**
 * What was typed is not a phone number anybody could be reached on.
 *
 * <p>Worth its own type rather than a bean-validation pattern, because the number is
 * rejected after normalisation rather than before it: {@code 07-00-00-00-00} is fine and
 * {@code 0700} is not, and no regular expression over the raw input says that clearly.
 */
public class InvalidPhoneNumberException extends RuntimeException {

    public InvalidPhoneNumberException(String raw) {
        super("'" + raw + "' is not a usable phone number. Enter it with the country "
                + "code, like +225 07 00 00 00 00.");
    }
}
