package com.microservices.cart.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.cart.support.FakeProductService;
import com.microservices.cart.support.LogCapture;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 9: app.logging.include-email=false removes the email from the logs.
 * The same settings also set a 1 ms limit for "slow", so a request that waits for product-service is flagged.
 */
@SpringBootTest(properties = {"app.logging.include-email=false", "app.logging.slow-request-ms=1"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class IncludeEmailSwitchTest {

    private static final FakeProductService PRODUCTS = new FakeProductService();

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("cart.product-service.url", PRODUCTS::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private LogCapture logs;

    @BeforeEach
    void start() {
        MDC.clear();
        PRODUCTS.reset();
        logs = LogCapture.start();
    }

    @AfterEach
    void stop() {
        logs.close();
        MDC.clear();
    }

    @Test
    void withTheSwitchOff_noEmailIsLogged_butTheUserIdAndRoleStay() throws Exception {
        long userId = TestTokens.newUserId();
        String email = "user" + userId + "@example.com";

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(userId))))
                .andExpect(status().isOk());

        JsonNode line = logs.singleAccessLine("/api/cart");
        assertThat(line.get("userId").asText()).isEqualTo(String.valueOf(userId));
        assertThat(line.get("userRole").asText()).isEqualTo("USER");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(logs.allText()).doesNotContain(email).doesNotContain("@example.com");
    }

    @Test
    void anAuditEvent_hasNoEmailEither() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        long userId = TestTokens.newUserId();

        mockMvc.perform(put("/api/cart/items/PHONE-1")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(userId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk());

        JsonNode event = logs.single("cart_item_set");
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(userId));
        assertThat(event.has("userEmail")).isFalse();
    }

    @Test
    void aRequestSlowerThanTheLimit_isWarn_withSlowTrue() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        PRODUCTS.delayMillis(40);

        mockMvc.perform(put("/api/cart/items/PHONE-1")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(TestTokens.newUserId())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk());

        JsonNode line = logs.singleAccessLine("/api/cart/items/PHONE-1");
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("slow").asBoolean()).isTrue();
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(40);
        assertThat(line.get("httpStatus").asInt()).isEqualTo(200);
    }
}
