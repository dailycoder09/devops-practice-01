package com.microservices.cart.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Base for the tests that call the real application (real security filter chain, real controller,
 * in-memory H2 with the real Flyway migration) through MockMvc. product-service is replaced by a
 * fake HTTP server on a random port. All subclasses use the same settings, so Spring starts the
 * application only once for all of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Tests switch the metrics export off by default; /actuator/prometheus needs it, as it does when running.
@AutoConfigureObservability
@ActiveProfiles("test")
public abstract class AbstractCartApiTest {

    protected static final FakeProductService PRODUCTS = FakeProductService.shared();

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("cart.product-service.url", PRODUCTS::baseUrl);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetFakeProductService() {
        PRODUCTS.reset();
    }

    protected ResultActions getCart(String token) throws Exception {
        return mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token)));
    }

    protected ResultActions putQuantity(String token, String sku, String jsonBody) throws Exception {
        return mockMvc.perform(put("/api/cart/items/{sku}", sku)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody));
    }

    protected ResultActions setQuantity(String token, String sku, int quantity) throws Exception {
        return putQuantity(token, sku, "{\"quantity\":" + quantity + "}");
    }

    protected ResultActions removeItem(String token, String sku) throws Exception {
        return mockMvc.perform(delete("/api/cart/items/{sku}", sku)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token)));
    }

    protected ResultActions clearCart(String token) throws Exception {
        return mockMvc.perform(delete("/api/cart").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token)));
    }

    protected JsonNode json(ResultActions result) throws Exception {
        // JSON is UTF-8, but MockMvc reads a response without a charset as ISO-8859-1
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
