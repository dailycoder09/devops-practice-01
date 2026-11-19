package com.microservices.order.service;

import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.dto.response.OrderStatsResponse;
import com.microservices.order.model.OrderStatus;
import org.springframework.data.domain.Page;

/**
 * Order Service
 *
 * Business logic interface for orders
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface OrderService {

    /**
     * Place an order from the caller's cart (see the numbered steps in OrderServiceImpl)
     *
     * @param caller the person who orders
     * @return the CONFIRMED order, with cartCleared
     * @throws com.microservices.order.exception.EmptyCartException              the cart is empty (400)
     * @throws com.microservices.order.exception.ProductNotAvailableException    a product is gone (422)
     * @throws com.microservices.order.exception.DependencyUnavailableException  cart or product service failed (503)
     * @throws com.microservices.order.exception.OrderRejectedException          stock short (409) or stock service failed (503)
     */
    OrderResponse placeOrder(Caller caller);

    /**
     * The caller's own orders, newest first
     *
     * @param caller the person who asks
     * @param page   page number, from 0
     * @param size   page size
     * @return a page of orders
     */
    Page<OrderResponse> listOrders(Caller caller, int page, int size);

    /**
     * One order, if it belongs to the caller or the caller is an admin
     *
     * @param caller the person who asks
     * @param id     the order id
     * @return the order
     * @throws com.microservices.order.exception.OrderNotFoundException if there is no such order or it is not visible to the caller
     */
    OrderResponse getOrder(Caller caller, long id);

    /**
     * The orders of ALL users, newest first (for the admin area; the caller's role is checked by the security rules)
     *
     * @param status only orders with this status, or null for every status
     * @param page   page number, from 0
     * @param size   page size
     * @return a page of orders
     */
    Page<OrderResponse> listAllOrders(OrderStatus status, int page, int size);

    /**
     * Numbers for the admin overview: orders per status and the revenue of the confirmed orders
     *
     * @return the statistics over the orders of all users
     */
    OrderStatsResponse stats();
}
