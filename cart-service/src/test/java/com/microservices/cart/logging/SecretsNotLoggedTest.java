package com.microservices.cart.logging;

import ch.qos.logback.classic.Level;
import com.microservices.cart.support.AbstractCartLoggingTest;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 5: after a flow that uses tokens (and fails in several ways), nothing secret is in the logs:
 * no token, no Authorization header, no JWT secret.
 */
class SecretsNotLoggedTest extends AbstractCartLoggingTest {

    private void runTheFlow(String token) throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        getCart(token).andExpect(status().isOk());
        putQuantity(token, "PHONE-1", "{\"quantity\": hunter2-in-broken-json").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token + "tampered"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        getCart(TestTokens.service()).andExpect(status().isForbidden());
        PRODUCTS.failWithStatus(500);
        getCart(token).andExpect(status().isServiceUnavailable());
        PRODUCTS.failWithStatus(0);
        mockMvc.perform(put("/api/cart/items/PHONE-1").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"seven\"}"))
                .andExpect(status().isBadRequest());
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        clearCart(token).andExpect(status().isNoContent());
    }

    private void assertNothingSecret(String token) {
        String text = logs.allText();

        assertThat(text)
                .doesNotContain(token)
                .doesNotContain(token.split("\\.")[1])
                .doesNotContain(TestTokens.service())
                .doesNotContain(TestTokens.SECRET)
                .doesNotContain("hunter2-in-broken-json")
                .doesNotContain("Bearer ");
    }

    @Test
    void atTheNormalLevels_nothingSecretIsLogged() throws Exception {
        String token = TestTokens.user(TestTokens.newUserId());

        runTheFlow(token);

        assertNothingSecret(token);
        assertThat(logs.allText()).doesNotContain("Authorization");
    }

    @Test
    void evenWithDebugOn_nothingSecretIsLogged() throws Exception {
        logs.level("ROOT", Level.DEBUG).level("com.microservices", Level.DEBUG);
        String token = TestTokens.user(TestTokens.newUserId());

        runTheFlow(token);

        assertNothingSecret(token);
    }
}
