package com.microservices.order.client;

/**
 * Talks to inventory-service with a short-lived internal SERVICE token that this service makes itself.
 * Both calls are repeat-safe on the inventory side (they are keyed by the order id).
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface InventoryClient {

    /**
     * Take stock for one line (POST /api/inventory/sku/{sku}/reserve)
     *
     * @param sku      Stock Keeping Unit code
     * @param quantity how many
     * @param orderId  the order the stock is taken for
     * @throws com.microservices.order.exception.StockRefusedException if inventory says 409 (not enough stock) or 404
     * @throws com.microservices.order.exception.StockServiceException if inventory is unreachable, too slow or fails
     */
    void reserve(String sku, int quantity, long orderId);

    /**
     * Give back the stock of one line (POST /api/inventory/sku/{sku}/release). A failure is logged, never thrown.
     *
     * @param sku     Stock Keeping Unit code
     * @param orderId the order the stock was taken for
     * @return true if inventory answered with success, false if anything went wrong
     */
    boolean release(String sku, long orderId);
}
