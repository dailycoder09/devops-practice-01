package com.microservices.order.client;

import java.util.Optional;

/**
 * Talks to product-service (GET only, public).
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface ProductClient {

    /**
     * Look up a product by SKU (GET /api/products/sku/{sku})
     *
     * @param sku Stock Keeping Unit code
     * @return the name and price, or empty if product-service has no such product (404)
     * @throws com.microservices.order.exception.DependencyUnavailableException if product-service does not give a usable answer
     */
    Optional<ProductInfo> findBySku(String sku);
}
