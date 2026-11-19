package com.microservices.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.order.support.CheckoutTestBase;
import com.microservices.order.support.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/orders and GET /api/orders/{id}: who may read which order, the list (own orders only, newest first)
 * and the paging (Review Focus 5).
 */
class OrderReadTest extends CheckoutTestBase {

    private static final String NOT_FOUND = "Order not found.";

    private JsonNode list(String token, String query) throws Exception {
        return json(mockMvc.perform(get("/api/orders" + query).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()));
    }

    // ================================================================== one order

    @Test
    void theOwner_canReadTheOrder_withoutCartCleared() throws Exception {
        long orderId = placeOrder(userId, "SKU-A", 2);

        getOrder(userToken, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(200.00))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.rejectReason").doesNotExist())
                // cartCleared belongs to the answer of POST only
                .andExpect(jsonPath("$.cartCleared").doesNotExist())
                .andExpect(jsonPath("$.items[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.items[0].name").value("Phone A"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(200.00));
    }

    @Test
    void aRejectedOrder_showsItsReason() throws Exception {
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        long orderId = json(checkout(userToken)).get("orderId").asLong();

        getOrder(userToken, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Not enough stock for SKU-B: wanted 9, available 5"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-B"));
    }

    @Test
    void anotherUsersOrder_is404_notForbidden() throws Exception {
        long orderId = placeOrder(userId, "SKU-A", 1);
        String otherUser = JwtTestSupport.userToken(userId + 5000);

        getOrder(otherUser, orderId)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(NOT_FOUND));
    }

    @Test
    void anUnknownOrder_givesTheSameAnswer_asAnotherUsersOrder() throws Exception {
        long orderId = placeOrder(userId, "SKU-A", 1);
        String otherUser = JwtTestSupport.userToken(userId + 5000);

        JsonNode foreign = json(getOrder(otherUser, orderId).andExpect(status().isNotFound()));
        JsonNode unknown = json(getOrder(otherUser, 987_654_321L).andExpect(status().isNotFound()));

        // Nothing in the answer tells "exists but not yours" from "does not exist".
        assertThat(foreign.get("detail")).isEqualTo(unknown.get("detail"));
        assertThat(foreign.get("title")).isEqualTo(unknown.get("title"));
        assertThat(foreign.toString()).doesNotContain("SKU-A");
    }

    @Test
    void anAdmin_canReadAnyOrder() throws Exception {
        long orderId = placeOrder(userId, "SKU-A", 1);

        getOrder(JwtTestSupport.adminToken(userId + 9000), orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-A"));
    }

    @Test
    void anAdmin_getsA404_forAnUnknownOrder() throws Exception {
        getOrder(JwtTestSupport.adminToken(userId), 987_654_321L).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void zeroAndNegativeIds_are404(String id) throws Exception {
        getOrder(userToken, id).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999999"})
    void anIdThatIsNotANumberOfTheRightSize_is400_notA500(String id) throws Exception {
        getOrder(userToken, id)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ================================================================== the list

    @Test
    void list_hasOnlyTheCallersOwnOrders_newestFirst() throws Exception {
        long other = userId + 7000;
        long first = placeOrder(userId, "SKU-C", 1);
        long foreign = placeOrder(other, "SKU-C", 1);
        long second = placeOrder(userId, "SKU-C", 2);
        long third = placeOrder(userId, "SKU-C", 3);

        JsonNode page = list(userToken, "");

        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("number").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(order -> ids.add(order.get("id").asLong()));
        assertThat(ids).containsExactly(third, second, first);
        assertThat(ids).doesNotContain(foreign);
        assertThat(page.get("content").get(0).get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(page.get("content").get(0).has("cartCleared")).isFalse();

        assertThat(list(JwtTestSupport.userToken(other), "").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void list_ofAnAdmin_isStillOnlyTheirOwnOrders() throws Exception {
        placeOrder(userId, "SKU-C", 1);

        JsonNode page = list(JwtTestSupport.adminToken(userId + 9500), "");

        assertThat(page.get("totalElements").asLong()).isZero();
        assertThat(page.get("content")).isEmpty();
    }

    @Test
    void list_ofAUserWithoutOrders_isAnEmptyPage() throws Exception {
        JsonNode page = list(userToken, "");

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @Test
    void list_showsRejectedOrdersToo_withTheirReason() throws Exception {
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        checkout(userToken).andExpect(status().isConflict());

        JsonNode page = list(userToken, "");

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(page.get("content").get(0).get("rejectReason").asText()).startsWith("Not enough stock for SKU-B");
    }

    @Test
    void list_isPaged() throws Exception {
        long first = placeOrder(userId, "SKU-C", 1);
        long second = placeOrder(userId, "SKU-C", 1);
        long third = placeOrder(userId, "SKU-C", 1);

        JsonNode pageOne = list(userToken, "?page=0&size=2");
        JsonNode pageTwo = list(userToken, "?page=1&size=2");

        assertThat(pageOne.get("totalElements").asLong()).isEqualTo(3);
        assertThat(pageOne.get("totalPages").asInt()).isEqualTo(2);
        assertThat(pageOne.get("size").asInt()).isEqualTo(2);
        assertThat(pageOne.get("content").get(0).get("id").asLong()).isEqualTo(third);
        assertThat(pageOne.get("content").get(1).get("id").asLong()).isEqualTo(second);
        assertThat(pageTwo.get("number").asInt()).isEqualTo(1);
        assertThat(pageTwo.get("content")).hasSize(1);
        assertThat(pageTwo.get("content").get(0).get("id").asLong()).isEqualTo(first);
    }

    @Test
    void list_aPageFarPastTheEnd_isEmpty_notAnError() throws Exception {
        placeOrder(userId, "SKU-C", 1);

        assertThat(list(userToken, "?page=50&size=100").get("content")).isEmpty();
        assertThat(list(userToken, "?page=100000&size=100").get("content")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=-5", "?size=101", "?size=abc", "?page=x", "?size=99999999999",
            "?page=100001", "?page=2147483647", "?page=2147483647&size=100"})
    void list_withBadPaging_is400_notA500(String query) throws Exception {
        mockMvc.perform(get("/api/orders" + query).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void list_ignoresASortParameter_orderStaysNewestFirst() throws Exception {
        long first = placeOrder(userId, "SKU-C", 1);
        long second = placeOrder(userId, "SKU-C", 1);

        JsonNode page = list(userToken, "?sort=id,asc");

        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(second);
        assertThat(page.get("content").get(1).get("id").asLong()).isEqualTo(first);
    }
}
