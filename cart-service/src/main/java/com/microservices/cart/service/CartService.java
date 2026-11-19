package com.microservices.cart.service;

import com.microservices.cart.dto.response.CartResponse;

/**
 * Cart Service
 *
 * The cart of one user. Every method gets the user id from the token, so a user only ever reaches their own cart.
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface CartService {

    /**
     * Show the cart with current names and prices from product-service
     *
     * @param userId the user
     * @return the cart
     * @throws com.microservices.cart.exception.ProductServiceUnavailableException if product-service gives no answer
     */
    CartResponse getCart(long userId);

    /**
     * Set the quantity of a product (adds the item, or replaces its quantity)
     *
     * @param userId   the user
     * @param sku      SKU code of the product
     * @param quantity new quantity, 1 to 99
     * @return the cart after the change
     * @throws com.microservices.cart.exception.UnknownProductException if the SKU does not exist in product-service
     * @throws com.microservices.cart.exception.ProductServiceUnavailableException if product-service gives no answer
     *         (nothing is changed in that case)
     */
    CartResponse setQuantity(long userId, String sku, int quantity);

    /**
     * Remove one item. Nothing happens if it is not in the cart.
     *
     * @param userId the user
     * @param sku    SKU code
     */
    void removeItem(long userId, String sku);

    /**
     * Remove all items of the user
     *
     * @param userId the user
     */
    void clear(long userId);
}
