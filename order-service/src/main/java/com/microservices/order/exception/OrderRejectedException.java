package com.microservices.order.exception;

import org.springframework.http.HttpStatus;

/**
 * The order was saved but could not be confirmed, so it is now REJECTED. The answer is 409 when the stock
 * was short, and 503 when a service failed. The body carries the id of the rejected order.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class OrderRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final long orderId;

    public OrderRejectedException(HttpStatus status, String detail, long orderId) {
        super(detail);
        this.status = status;
        this.orderId = orderId;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public long getOrderId() {
        return orderId;
    }
}
