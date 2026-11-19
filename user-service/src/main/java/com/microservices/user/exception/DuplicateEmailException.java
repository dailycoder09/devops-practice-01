package com.microservices.user.exception;

/**
 * Duplicate Email Exception
 *
 * Thrown when someone registers with an email that is already used
 *
 * @author Microservices Team
 * @version 1.0
 */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("An account with this email already exists.");
    }
}
