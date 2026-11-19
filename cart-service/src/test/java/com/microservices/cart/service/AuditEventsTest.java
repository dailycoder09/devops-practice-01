package com.microservices.cart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.support.AbstractCartLoggingTest;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit events of cart-service: cart_item_set, cart_item_removed and cart_cleared.
 * Each is written once per action, by the logger "audit", with the fields of the contract.
 * Who did it comes from the MDC.
 */
class AuditEventsTest extends AbstractCartLoggingTest {

    @Autowired
    private CartService cartService;

    @Test
    void settingAQuantity_writesCartItemSet_once_withSkuQuantityAndTheUser() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        long userId = TestTokens.newUserId();

        setQuantity(TestTokens.user(userId), "PHONE-1", 3).andExpect(status().isOk());

        JsonNode event = logs.single("cart_item_set");
        assertThat(event.get("logger_name").asText()).isEqualTo("audit");
        assertThat(event.get("level").asText()).isEqualTo("INFO");
        assertThat(event.get("sku").asText()).isEqualTo("PHONE-1");
        assertThat(event.get("quantity").asInt()).isEqualTo(3);
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(userId));
        assertThat(event.get("userEmail").asText()).isEqualTo("user" + userId + "@example.com");
        assertThat(event.get("userRole").asText()).isEqualTo("USER");
        assertThat(event.get("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(event.get("traceId").asText())
                .isEqualTo(logs.singleAccessLine("/api/cart/items/PHONE-1").get("traceId").asText());
    }

    @Test
    void everyChangeOfTheQuantity_isOneEvent_withItsOwnQuantity() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        String token = TestTokens.user(TestTokens.newUserId());

        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        setQuantity(token, "PHONE-1", 5).andExpect(status().isOk());

        assertThat(logs.events("cart_item_set").stream().map(event -> event.get("quantity").asInt()))
                .containsExactly(2, 5);
    }

    @Test
    void anAdminToken_isLoggedAsTheAdmin() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        long adminId = TestTokens.newUserId();

        setQuantity(TestTokens.admin(adminId), "PHONE-1", 1).andExpect(status().isOk());

        JsonNode event = logs.single("cart_item_set");
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(adminId));
        assertThat(event.get("userRole").asText()).isEqualTo("ADMIN");
    }

    @Test
    void whenNothingWasChanged_noCartItemSetIsWritten() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        String token = TestTokens.user(TestTokens.newUserId());

        setQuantity(token, "UNKNOWN-SKU", 1).andExpect(status().isUnprocessableEntity());
        setQuantity(token, "PHONE-1", 0).andExpect(status().isBadRequest());
        setQuantity(token, "PHONE-1", 100).andExpect(status().isBadRequest());
        PRODUCTS.failWithStatus(500);
        setQuantity(token, "PHONE-1", 1).andExpect(status().isServiceUnavailable());
        PRODUCTS.reset();
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        setQuantity(TestTokens.service(), "PHONE-1", 1).andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/cart/items/PHONE-1").contentType("application/json").content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());

        assertThat(logs.events("cart_item_set")).isEmpty();
    }

    @Test
    void removingAnItem_writesCartItemRemoved_once_withTheSku() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        long userId = TestTokens.newUserId();
        String token = TestTokens.user(userId);
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());

        removeItem(token, "PHONE-1").andExpect(status().isNoContent());

        JsonNode event = logs.single("cart_item_removed");
        assertThat(event.get("logger_name").asText()).isEqualTo("audit");
        assertThat(event.get("level").asText()).isEqualTo("INFO");
        assertThat(event.get("sku").asText()).isEqualTo("PHONE-1");
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(userId));
        assertThat(logs.events("cart_cleared")).isEmpty();
    }

    @Test
    void everyDeleteCall_isOneEvent_evenWhenTheItemWasNotThere() throws Exception {
        String token = TestTokens.user(TestTokens.newUserId());

        removeItem(token, "NOT-IN-THE-CART").andExpect(status().isNoContent());
        removeItem(token, "NOT-IN-THE-CART").andExpect(status().isNoContent());

        assertThat(logs.events("cart_item_removed")).hasSize(2);
    }

    @Test
    void emptyingTheCart_writesCartCleared_once() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        long userId = TestTokens.newUserId();
        String token = TestTokens.user(userId);
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());

        clearCart(token).andExpect(status().isNoContent());

        JsonNode event = logs.single("cart_cleared");
        assertThat(event.get("logger_name").asText()).isEqualTo("audit");
        assertThat(event.get("level").asText()).isEqualTo("INFO");
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(userId));
        assertThat(event.get("userRole").asText()).isEqualTo("USER");
        assertThat(logs.events("cart_item_removed")).isEmpty();
    }

    @Test
    void aSkuWithLineBreaks_inTheServiceCall_makesNoSecondLineAndNoNewField() {
        long userId = TestTokens.newUserId();

        cartService.removeItem(userId, "AB\r\n{\"event\":\"fake\",\"level\":\"ERROR\"}");

        JsonNode event = logs.single("cart_item_removed");
        assertThat(event.get("sku").asText()).startsWith("AB__{").doesNotContain("\n").doesNotContain("\r");
        assertThat(logs.events("fake")).isEmpty();
    }

    @Test
    void aVeryLongSku_isCutTo200Characters() {
        cartService.removeItem(TestTokens.newUserId(), "S".repeat(5000));

        assertThat(logs.single("cart_item_removed").get("sku").asText()).hasSize(200);
    }

    @Test
    void theEvents_neverCarryTheProductNameOrThePrice() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Distinctive Phone Name", "1234.56");
        String token = TestTokens.user(TestTokens.newUserId());

        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        clearCart(token).andExpect(status().isNoContent());

        assertThat(logs.allText()).doesNotContain("Distinctive Phone Name").doesNotContain("1234.56");
    }
}
