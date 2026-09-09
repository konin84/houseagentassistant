package com.digitalpartner.houseagent.agency.identity;

/** The identity provider refused or could not be reached. */
public class DirectoryException extends RuntimeException {

    public DirectoryException(String message) {
        super(message);
    }

    public DirectoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
