package com.microservices.order.exception;

/**
 * The cart has no items, so there is nothing to order (answer 400).
 *
 * @author Microservices Team
 * @version 1.0
 */
public class EmptyCartException extends RuntimeException {

    public EmptyCartException() {
        super("Your cart is empty.");
    }
}
