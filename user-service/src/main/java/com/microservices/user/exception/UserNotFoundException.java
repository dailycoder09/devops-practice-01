package com.microservices.user.exception;

/**
 * User Not Found Exception
 *
 * Thrown when the user behind a valid token no longer exists
 *
 * @author Microservices Team
 * @version 1.0
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super(String.format("User not found with id: %d", id));
    }
}
