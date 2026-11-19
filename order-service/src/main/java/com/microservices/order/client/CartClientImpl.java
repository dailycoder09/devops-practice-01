package com.microservices.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.microservices.order.exception.DependencyUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * Cart Client Implementation
 *
 * Calls GET and DELETE /api/cart on cart-service with the caller's own Authorization header, so the cart
 * service itself decides whose cart it is. The checkout never names a user to the cart service.
 *
 * Reading must give a usable answer: anything else (time-out, error status, a body that makes no sense)
 * is {@link DependencyUnavailableException}, and nothing is saved. Clearing is best effort and never throws.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@Slf4j
public class CartClientImpl implements CartClient {

    static final int MAX_SKU_LENGTH = 50;
    static final int MAX_QUANTITY = 999;

    private final RestClient cartRestClient;

    public CartClientImpl(@Qualifier("cartRestClient") RestClient cartRestClient) {
        this.cartRestClient = cartRestClient;
    }

    @Override
    public List<CartLine> readCart(String authorization) {
        CartAnswer answer;
        try {
            answer = cartRestClient.get()
                    .uri("/api/cart")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(CartAnswer.class);
        } catch (RestClientException ex) {
            // Not logged here: DownstreamLoggingInterceptor logs the call itself (status or exception class),
            // and GlobalExceptionHandler logs the 503 once.
            throw DependencyUnavailableException.cart(ex);
        }

        if (answer == null || answer.items() == null) {
            log.warn("Reading the cart failed: the answer has no list of items");
            throw DependencyUnavailableException.cart(null);
        }
        List<CartLine> lines = new ArrayList<>();
        for (CartAnswerLine item : answer.items()) {
            if (item == null || item.sku() == null || item.sku().isBlank() || item.sku().length() > MAX_SKU_LENGTH
                    || item.quantity() == null || item.quantity() < 1 || item.quantity() > MAX_QUANTITY) {
                log.warn("Reading the cart failed: a line of the answer is not valid");
                throw DependencyUnavailableException.cart(null);
            }
            lines.add(new CartLine(item.sku(), item.quantity()));
        }
        return lines;
    }

    @Override
    public boolean clear(String authorization) {
        try {
            cartRestClient.delete()
                    .uri("/api/cart")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            // The failed call is logged by DownstreamLoggingInterceptor; the checkout adds the cart_clear_failed event.
            return false;
        }
    }

    /**
     * Only the fields that checkout reads. The cart also sends names and prices, which are ignored on purpose.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CartAnswer(List<CartAnswerLine> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CartAnswerLine(String sku, Integer quantity) {
    }
}
