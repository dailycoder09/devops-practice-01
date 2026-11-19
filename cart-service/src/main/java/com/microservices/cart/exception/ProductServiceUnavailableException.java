package com.microservices.cart.exception;

/**
 * Thrown when product-service cannot give a usable answer (not reachable, too slow, error, unreadable answer).
 * Answered with 503 and a plain sentence.
 */
public class ProductServiceUnavailableException extends RuntimeException {

    public static final String MESSAGE = "The product service did not answer. Please try again.";

    public ProductServiceUnavailableException() {
        super(MESSAGE);
    }

    public ProductServiceUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
