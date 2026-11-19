package com.microservices.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.microservices.order.exception.DependencyUnavailableException;
import com.microservices.order.logging.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Product Client Implementation
 *
 * Calls GET /api/products/sku/{sku} on product-service (public, no token). A 404 means the product is gone
 * (empty result). Any other failure, or an answer without a usable name and price, is
 * {@link DependencyUnavailableException}: checkout must not guess a price.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@Slf4j
public class ProductClientImpl implements ProductClient {

    /**
     * The largest price that fits the column DECIMAL(10,2)
     */
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    private static final int MAX_NAME_LENGTH = 255;

    private final RestClient productRestClient;

    public ProductClientImpl(@Qualifier("productRestClient") RestClient productRestClient) {
        this.productRestClient = productRestClient;
    }

    @Override
    public Optional<ProductInfo> findBySku(String sku) {
        ProductAnswer answer;
        try {
            answer = productRestClient.get()
                    .uri("/api/products/sku/{sku}", sku)
                    .retrieve()
                    .body(ProductAnswer.class);
        } catch (HttpClientErrorException.NotFound ex) {
            log.debug("Product with SKU '{}' not found (404)", LogSanitizer.clean(sku));
            return Optional.empty();
        } catch (RestClientException ex) {
            // The call itself is logged by DownstreamLoggingInterceptor, the 503 once by GlobalExceptionHandler.
            throw DependencyUnavailableException.product(ex);
        }

        if (answer == null || answer.name() == null || answer.name().isBlank() || answer.price() == null
                || answer.price().signum() < 0) {
            log.warn("Looking up product '{}' failed: the answer has no usable name and price", LogSanitizer.clean(sku));
            throw DependencyUnavailableException.product(null);
        }
        BigDecimal price = answer.price().setScale(2, RoundingMode.HALF_UP);
        if (price.compareTo(MAX_PRICE) > 0) {
            log.warn("Looking up product '{}' failed: the price is too large", LogSanitizer.clean(sku));
            throw DependencyUnavailableException.product(null);
        }
        String name = answer.name().trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        return Optional.of(new ProductInfo(name, price));
    }

    /**
     * Only the fields that checkout reads. Everything else product-service sends is ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProductAnswer(String name, BigDecimal price) {
    }
}
