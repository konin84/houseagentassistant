package com.digitalpartner.houseagent.agency.service;

/**
 * An attempt to hand out a role the caller does not have the standing to grant.
 *
 * <p>The case that matters is an agency admin creating another agency admin. A role
 * that can grant itself is not a boundary: one compromised account becomes as many as
 * the attacker wants, and removing the original achieves nothing.
 */
public class RoleNotGrantableException extends RuntimeException {

    public RoleNotGrantableException(String message) {
        super(message);
    }
}
