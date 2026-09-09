package com.digitalpartner.houseagent.agency.service;

/**
 * The email address already belongs to an account that cannot be adopted.
 *
 * <p>The message never says whose account it is or which agency they belong to. An
 * agency admin is allowed to learn that an address is taken - they cannot proceed
 * otherwise - but not who has it.
 */
public class AlreadyRegisteredException extends RuntimeException {

    public AlreadyRegisteredException(String message) {
        super(message);
    }
}
