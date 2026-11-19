package com.microservices.cart.client;

import java.util.Optional;

/**
 * Product Client
 *
 * Asks product-service about a product, by SKU.
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface ProductClient {

    /**
     * Find a product by its SKU code
     *
     * @param sku SKU code
     * @return the product, or empty if product-service says the SKU does not exist (404)
     * @throws com.microservices.cart.exception.ProductServiceUnavailableException if product-service cannot be
     *         reached, is too slow, answers with an error, or gives an answer that cannot be used
     */
    Optional<ProductInfo> findBySku(String sku);
}
