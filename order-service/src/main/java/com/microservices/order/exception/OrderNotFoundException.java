package com.microservices.order.exception;

/**
 * There is no such order, or it belongs to someone else (answer 404 in both cases, so existence does not leak).
 *
 * @author Microservices Team
 * @version 1.0
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("Order not found.");
    }
}
