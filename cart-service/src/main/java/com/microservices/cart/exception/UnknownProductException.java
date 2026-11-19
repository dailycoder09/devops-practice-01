package com.microservices.cart.exception;

/**
 * Thrown when a product (SKU) does not exist in product-service. Answered with 422.
 */
public class UnknownProductException extends RuntimeException {

    private static final int SHOWN_SKU_LENGTH = 50;

    public UnknownProductException(String sku) {
        super("Unknown product: " + shorten(sku));
    }

    /** A SKU is at most 50 characters. A longer text from a caller is cut, so the answer stays short. */
    private static String shorten(String sku) {
        if (sku == null) {
            return "";
        }
        return sku.length() <= SHOWN_SKU_LENGTH ? sku : sku.substring(0, SHOWN_SKU_LENGTH) + "...";
    }
}
