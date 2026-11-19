package com.microservices.order.exception;

/**
 * A product in the cart is not in product-service any more (answer 422).
 *
 * @author Microservices Team
 * @version 1.0
 */
public class ProductNotAvailableException extends RuntimeException {

    public ProductNotAvailableException(String sku) {
        super("Product " + sku + " is no longer available.");
    }
}
