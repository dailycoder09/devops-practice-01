package com.microservices.order.client;

import java.math.BigDecimal;

/**
 * What checkout needs to know about a product.
 *
 * @param name  the product name
 * @param price the unit price, two decimals
 */
public record ProductInfo(String name, BigDecimal price) {
}
