package com.microservices.cart.controller;

import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-service is not there at all (nothing listens on port 1): the answer is 503 with a plain sentence,
 * never a 500, and the cart is not changed.
 */
@SpringBootTest(properties = "cart.product-service.url=http://localhost:1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductServiceUnreachableTest {

    private static final String DETAIL = "The product service did not answer. Please try again.";

    @Autowired
    private MockMvc mockMvc;

    private final String auth = TestTokens.bearer(TestTokens.user(TestTokens.newUserId()));

    @Test
    void put_gives503_andSavesNothing() throws Exception {
        mockMvc.perform(put("/api/cart/items/PHONE-1").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(DETAIL));

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void delete_stillWorks() throws Exception {
        mockMvc.perform(delete("/api/cart/items/PHONE-1").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/cart").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
    }
}
