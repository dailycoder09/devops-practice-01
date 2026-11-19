package com.microservices.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.exception.StockRefusedException;
import com.microservices.order.exception.StockServiceException;
import com.microservices.order.service.ServiceTokenIssuer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Inventory Client Implementation
 *
 * Calls POST .../reserve and POST .../release on inventory-service. Both carry a fresh internal SERVICE token
 * (a token lives only 2 minutes, so each call gets its own).
 *
 * reserve: 200 is success. 409 (not enough stock) and 404 (no stock record) are a clear "no"
 * ({@link StockRefusedException}). Everything else (connection refused, time-out, 5xx, 401, 403, 400, ...)
 * means this service cannot tell what happened, so it is a {@link StockServiceException}.
 *
 * release: success or failure is only a yes or no, because the caller must go on with the other lines anyway.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class InventoryClientImpl implements InventoryClient {

    /**
     * The reason is stored in a column of 500 characters
     */
    static final int MAX_REASON_LENGTH = 500;

    private final RestClient inventoryRestClient;
    private final ServiceTokenIssuer tokenIssuer;
    private final ObjectMapper objectMapper;

    public InventoryClientImpl(@Qualifier("inventoryRestClient") RestClient inventoryRestClient,
                               ServiceTokenIssuer tokenIssuer, ObjectMapper objectMapper) {
        this.inventoryRestClient = inventoryRestClient;
        this.tokenIssuer = tokenIssuer;
        this.objectMapper = objectMapper;
    }

    @Override
    public void reserve(String sku, int quantity, long orderId) {
        try {
            inventoryRestClient.post()
                    .uri("/api/inventory/sku/{sku}/reserve?quantity={quantity}&orderId={orderId}",
                            sku, quantity, orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue())
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict ex) {
            // Not logged here: the call is logged by DownstreamLoggingInterceptor, the refusal by the order_rejected event.
            throw new StockRefusedException(conflictReason(ex, sku));
        } catch (HttpClientErrorException.NotFound ex) {
            throw new StockRefusedException("No stock record for SKU " + sku);
        } catch (RestClientException ex) {
            throw new StockServiceException("The stock service did not answer", ex);
        }
    }

    @Override
    public boolean release(String sku, long orderId) {
        try {
            inventoryRestClient.post()
                    .uri("/api/inventory/sku/{sku}/release?orderId={orderId}", sku, orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            // The caller logs the release_failed event (with the order id and the SKU); the call itself is logged by
            // DownstreamLoggingInterceptor.
            return false;
        }
    }

    /**
     * The "detail" text of the 409 problem JSON, for example "Not enough stock for SKU-1: wanted 3, available 1".
     * If there is none, a short sentence of our own.
     */
    private String conflictReason(HttpClientErrorException ex, String sku) {
        try {
            ProblemAnswer problem = objectMapper.readValue(ex.getResponseBodyAsString(), ProblemAnswer.class);
            if (problem != null && problem.detail() != null && !problem.detail().isBlank()) {
                String detail = problem.detail().trim();
                return detail.length() > MAX_REASON_LENGTH ? detail.substring(0, MAX_REASON_LENGTH) : detail;
            }
        } catch (Exception ignored) {
            // The body is not the JSON we expect: use the plain sentence below.
        }
        return "Not enough stock for " + sku;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProblemAnswer(String detail) {
    }
}
