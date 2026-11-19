package com.microservices.inventory.exception;

/**
 * Insufficient Stock Exception
 *
 * Thrown when a reserve asks for more units than are on hand
 *
 * @author Microservices Team
 * @version 1.0
 */
public class InsufficientStockException extends RuntimeException {

    /**
     * Constructs exception with the SKU, the wanted units and the units on hand
     *
     * @param skuCode SKU code that is short
     * @param wanted units that were asked for
     * @param available units on hand
     */
    public InsufficientStockException(String skuCode, int wanted, int available) {
        super(String.format("Not enough stock for SKU %s: wanted %d, available %d", skuCode, wanted, available));
    }
}
