package com.microservices.order.exception;

/**
 * The token is valid but its "sub" claim is not a user id (answer 401).
 *
 * @author Microservices Team
 * @version 1.0
 */
public class InvalidTokenSubjectException extends RuntimeException {

    public InvalidTokenSubjectException() {
        super("A valid login token is needed. Please log in again.");
    }
}
