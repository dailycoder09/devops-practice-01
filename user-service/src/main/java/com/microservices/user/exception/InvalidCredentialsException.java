package com.microservices.user.exception;

/**
 * Invalid Credentials Exception
 *
 * Thrown when login fails. The message is the same whether the email is unknown or the
 * password is wrong, so nobody can use it to find out which emails have an account.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class InvalidCredentialsException extends RuntimeException {

    public static final String MESSAGE = "Wrong email or password.";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
