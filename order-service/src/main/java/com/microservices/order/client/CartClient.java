package com.microservices.order.client;

import java.util.List;

/**
 * Talks to cart-service with the caller's own token.
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface CartClient {

    /**
     * Read the caller's cart (GET /api/cart)
     *
     * @param authorization the caller's own Authorization header value ("Bearer ...")
     * @return the lines of the cart (empty when the cart is empty)
     * @throws com.microservices.order.exception.DependencyUnavailableException if cart-service does not give a usable answer
     */
    List<CartLine> readCart(String authorization);

    /**
     * Empty the caller's cart (DELETE /api/cart). It is a best-effort clean-up, so it never throws.
     *
     * @param authorization the caller's own Authorization header value ("Bearer ...")
     * @return true if the cart was emptied, false if anything went wrong
     */
    boolean clear(String authorization);
}
