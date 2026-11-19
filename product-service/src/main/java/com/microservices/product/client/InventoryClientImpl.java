package com.microservices.product.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.microservices.product.logging.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * Inventory Client Implementation
 *
 * Calls inventory-service's GET /api/inventory/sku/{skuCode} endpoint using Spring 6.1's RestClient
 * to enrich a single-item product read with live stock info.
 *
 * Note on the asymmetry: this is intentionally the opposite failure-handling style from
 * inventory-service's own ProductClient (which throws on network/timeout/5xx, since that call
 * guards a write-path validation) - here, every failure (404, timeout, connection refused, 5xx,
 * malformed response) is swallowed into Optional.empty() because this is a read-path enrichment
 * call that must never break the primary product read.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryClientImpl implements InventoryClient {

    private final RestClient inventoryRestClient;

    /**
     * Find current stock quantity for a given SKU code
     *
     * @param skuCode SKU code to look up
     * @return Optional containing quantity on hand, or empty if unknown/unavailable - never throws
     */
    @Override
    public Optional<Integer> findStockBySkuCode(String skuCode) {
        try {
            InventoryStockResponse stock = inventoryRestClient.get()
                    .uri("/api/inventory/sku/{skuCode}", skuCode)
                    .retrieve()
                    .body(InventoryStockResponse.class);

            return stock != null ? Optional.ofNullable(stock.quantityOnHand()) : Optional.empty();
        } catch (HttpClientErrorException.NotFound ex) {
            log.debug("No inventory record found for SKU: {}", LogSanitizer.clean(skuCode));
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException ex) {
            // an error answer or an I/O problem: the DownstreamLoggingInterceptor has already written the one WARN line
            log.debug("Inventory lookup failed for SKU: {} ({})", LogSanitizer.clean(skuCode), ex.getClass().getSimpleName());
            return Optional.empty();
        } catch (RestClientException ex) {
            // for example an answer that cannot be read: the interceptor saw a good answer, so say it here
            log.warn("Inventory answer for SKU: {} could not be used ({})", LogSanitizer.clean(skuCode), ex.getClass().getSimpleName());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Unexpected error during inventory lookup for SKU: {} ({})", LogSanitizer.clean(skuCode), ex.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * Minimal local projection of inventory-service's response - intentionally not reusing
     * inventory-service's DTOs, since the two services are independently deployable and own
     * their own API contracts. Unknown fields (id, skuCode, reorderThreshold, timestamps, etc.)
     * are ignored; only quantityOnHand is needed here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InventoryStockResponse(Integer quantityOnHand) {
    }
}
