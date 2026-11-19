package com.microservices.order.exception;

/**
 * The cart service or the product service did not give a usable answer. Nothing was saved (answer 503).
 *
 * @author Microservices Team
 * @version 1.0
 */
public class DependencyUnavailableException extends RuntimeException {

    public static final String CART_DETAIL = "The cart service did not answer. Please try again.";
    public static final String PRODUCT_DETAIL = "The product service did not answer. Please try again.";

    public DependencyUnavailableException(String detail, Throwable cause) {
        super(detail, cause);
    }

    public static DependencyUnavailableException cart(Throwable cause) {
        return new DependencyUnavailableException(CART_DETAIL, cause);
    }

    public static DependencyUnavailableException product(Throwable cause) {
        return new DependencyUnavailableException(PRODUCT_DETAIL, cause);
    }
}
