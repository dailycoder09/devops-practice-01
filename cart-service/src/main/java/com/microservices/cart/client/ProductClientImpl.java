package com.microservices.cart.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.microservices.cart.exception.ProductServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Product Client Implementation
 *
 * Calls product-service's public GET /api/products/sku/{sku}. No token is sent: the lookup is public, and the
 * login token of the user must never be passed on to another service.
 *
 * "Not found" (404) is a normal answer: the product does not exist (any more). Everything else that is not a
 * good answer (no connection, time-out, error status, unreadable or incomplete body) is reported as
 * ProductServiceUnavailableException, because then the cart cannot tell whether the product exists.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductClientImpl implements ProductClient {

    private final RestClient productRestClient;

    @Override
    public Optional<ProductInfo> findBySku(String sku) {
        try {
            ProductPayload payload = productRestClient.get()
                    .uri("/api/products/sku/{sku}", sku)
                    .retrieve()
                    .body(ProductPayload.class);

            if (payload == null || payload.name() == null || payload.name().isBlank()
                    || payload.price() == null || payload.price().signum() < 0) {
                log.warn("product-service answered 200, but without a usable name and price");
                throw new ProductServiceUnavailableException();
            }
            return Optional.of(new ProductInfo(payload.name(), payload.price()));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (ProductServiceUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // A refused connection, a time-out and an error status are already logged, once, by the
            // DownstreamLoggingInterceptor (and the request fails with one line from the exception handler).
            // Only an answer that arrived but could not be read is not seen by the interceptor.
            if (!(ex instanceof ResourceAccessException) && !(ex instanceof RestClientResponseException)) {
                log.warn("product-service answered, but the answer could not be read: {}",
                        ex.getClass().getSimpleName());
            }
            throw new ProductServiceUnavailableException(ex);
        }
    }

    /**
     * Minimal local projection of product-service's response - intentionally not reusing product-service's DTOs,
     * since the services are independently deployable and own their API contracts. Only the name and the price
     * are used; all other fields are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProductPayload(String name, BigDecimal price) {
    }
}
