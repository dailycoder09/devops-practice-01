package com.microservices.order.client;

/**
 * One line of the cart, as far as checkout needs it: the SKU and how many.
 * (The price comes from product-service, never from the cart.)
 *
 * @param sku      Stock Keeping Unit code
 * @param quantity how many, 1 to 999
 */
public record CartLine(String sku, int quantity) {
}
