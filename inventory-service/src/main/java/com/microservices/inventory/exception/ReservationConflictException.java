package com.microservices.inventory.exception;

/**
 * Reservation Conflict Exception
 *
 * Thrown when a reserve repeats an order and SKU that already has a reservation which cannot be
 * answered with "done": it was already released, or it was made with another quantity
 *
 * @author Microservices Team
 * @version 1.0
 */
public class ReservationConflictException extends RuntimeException {

    /**
     * Constructs exception with a plain sentence
     *
     * @param message what is wrong
     */
    public ReservationConflictException(String message) {
        super(message);
    }

    /**
     * The line of this order was already released
     *
     * @param orderId order id
     * @param skuCode SKU code
     * @return the exception
     */
    public static ReservationConflictException alreadyReleased(long orderId, String skuCode) {
        return new ReservationConflictException(String.format(
                "Order %d already released its stock for SKU %s, so it cannot be reserved again", orderId, skuCode));
    }

    /**
     * The line of this order was reserved with another quantity
     *
     * @param orderId order id
     * @param skuCode SKU code
     * @param reserved quantity that is already reserved
     * @param wanted quantity that was asked for now
     * @return the exception
     */
    public static ReservationConflictException otherQuantity(long orderId, String skuCode, int reserved, int wanted) {
        return new ReservationConflictException(String.format(
                "Order %d already reserved %d unit(s) of SKU %s, so it cannot reserve %d", orderId, reserved, skuCode, wanted));
    }
}
