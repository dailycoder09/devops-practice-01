package com.microservices.order.exception;

/**
 * inventory-service said no for one line: not enough stock (409) or no stock record for the SKU (404).
 * The message is the reason that is stored with the rejected order and shown to the person.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class StockRefusedException extends RuntimeException {

    public StockRefusedException(String reason) {
        super(reason);
    }
}
