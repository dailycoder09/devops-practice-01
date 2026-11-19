package com.microservices.order.model;

/**
 * Where an order is in its life.
 *
 * PENDING: saved, stock is being reserved. CONFIRMED: all stock reserved. REJECTED: stock was short
 * or a service did not answer (the reason is stored with the order).
 *
 * @author Microservices Team
 * @version 1.0
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}
