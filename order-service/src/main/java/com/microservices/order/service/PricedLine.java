package com.microservices.order.service;

import java.math.BigDecimal;

/**
 * One line of a new order, with the name and price that product-service gave at checkout.
 *
 * @param sku       Stock Keeping Unit code
 * @param name      product name
 * @param unitPrice unit price, two decimals
 * @param quantity  how many
 */
public record PricedLine(String sku, String name, BigDecimal unitPrice, int quantity) {
}
