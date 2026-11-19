package com.microservices.cart.controller;

import com.microservices.cart.support.FakeProductService;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-service is up but too slow: after the read time-out the answer is 503. The time-out is set to
 * 400 ms here so the test is fast (the real default is 5 s, see DevProfileDefaultsTest).
 */
@SpringBootTest(properties = "cart.product-service.read-timeout-ms=400")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductServiceSlowTest {

    private static final FakeProductService SLOW_PRODUCTS = new FakeProductService();

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("cart.product-service.url", SLOW_PRODUCTS::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        SLOW_PRODUCTS.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aSlowProductService_gives503_afterTheTimeout_andSavesNothing() throws Exception {
        SLOW_PRODUCTS.reset();
        SLOW_PRODUCTS.addProduct("PHONE-1", "Test Phone", "999.99");
        SLOW_PRODUCTS.delayMillis(2000);
        String auth = TestTokens.bearer(TestTokens.user(TestTokens.newUserId()));

        long start = System.nanoTime();
        mockMvc.perform(put("/api/cart/items/PHONE-1").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("gave up after the time-out, not after the whole delay").isLessThan(1800);

        SLOW_PRODUCTS.delayMillis(0);
        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void anAnswerInsideTheTimeout_isFine() throws Exception {
        SLOW_PRODUCTS.reset();
        SLOW_PRODUCTS.addProduct("PHONE-1", "Test Phone", "999.99");
        SLOW_PRODUCTS.delayMillis(100);
        String auth = TestTokens.bearer(TestTokens.user(TestTokens.newUserId()));

        mockMvc.perform(put("/api/cart/items/PHONE-1").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
    }
}
