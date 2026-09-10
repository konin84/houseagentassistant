package com.digitalpartner.houseagent.agency.service;

/**
 * Signup could not complete for a reason the caller cannot fix by editing their form.
 *
 * <p>Distinct from {@link AgencyAlreadyExistsException}, which on the signup path is not
 * an error at all - a taken id is simply the next id being tried.
 */
public class SignupFailedException extends RuntimeException {

    public SignupFailedException(String message) {
        super(message);
    }
}
