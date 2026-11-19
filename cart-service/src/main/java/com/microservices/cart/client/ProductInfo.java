package com.microservices.cart.client;

import java.math.BigDecimal;

/**
 * What the cart needs to know about a product: its name and its current price.
 *
 * @param name  product name
 * @param price current unit price
 */
public record ProductInfo(String name, BigDecimal price) {
}
