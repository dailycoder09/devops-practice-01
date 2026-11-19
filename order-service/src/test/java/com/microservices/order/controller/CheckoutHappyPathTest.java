package com.microservices.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.order.support.CheckoutTestBase;
import com.microservices.order.support.FakeHttpServer.Reply;
import com.microservices.order.support.FakeHttpServer.Request;
import com.microservices.order.support.JwtTestSupport;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/orders when everything works (steps 1 to 5 of the flow), and the cases that end before any order is saved
 * or that must answer 400.
 */
class CheckoutHappyPathTest extends CheckoutTestBase {

    @Test
    void checkout_withEnoughStock_confirmsTheOrder_andAnswers201() throws Exception {
        // The cart shows an old price for SKU-A (90.00); product-service says 100.00.
        shop.addToCart(userId, "SKU-A", 2, "90.00");
        shop.addToCart(userId, "SKU-B", 3, "20.50");

        var result = checkout(userToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cartCleared").value(true))
                .andExpect(jsonPath("$.rejectReason").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(2));

        JsonNode order = json(result);
        assertThat(order.get("id").asLong()).isPositive();
        assertThat(order.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("261.50");

        JsonNode first = order.get("items").get(0);
        assertThat(first.get("sku").asText()).isEqualTo("SKU-A");
        assertThat(first.get("name").asText()).isEqualTo("Phone A");
        // The price is the product-service price at checkout, never the one the cart shows (Review Focus 3).
        assertThat(first.get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(first.get("quantity").asInt()).isEqualTo(2);
        assertThat(first.get("lineTotal").decimalValue()).isEqualByComparingTo("200.00");
        JsonNode second = order.get("items").get(1);
        assertThat(second.get("sku").asText()).isEqualTo("SKU-B");
        assertThat(second.get("lineTotal").decimalValue()).isEqualByComparingTo("61.50");

        long orderId = order.get("id").asLong();
        assertThat(statusOf(orderId)).isEqualTo("CONFIRMED");
        assertThat(lineCount(orderId)).isEqualTo(2);
        assertThat(shop.stockOf("SKU-A")).isEqualTo(8);
        assertThat(shop.stockOf("SKU-B")).isEqualTo(2);
        assertThat(shop.cartSize(userId)).isZero();
        assertThat(releaseCalls()).isEmpty();
    }

    @Test
    void money_isShownWithTwoDecimals() throws Exception {
        shop.addToCart(userId, "SKU-C", 1, "5.00");

        String body = checkout(userToken).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"total\":5.00").contains("\"unitPrice\":5.00").contains("\"lineTotal\":5.00");
    }

    @Test
    void theOrderStoresTheUserIdAndEmailFromTheToken() throws Exception {
        shop.addToCart(userId, "SKU-C", 1, "5.00");

        long orderId = json(checkout(userToken)).get("id").asLong();

        assertThat(jdbc.queryForObject("SELECT user_id FROM orders WHERE id = ?", Long.class, orderId))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT user_email FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("user" + userId + "@example.com");
    }

    @Test
    void stockCalls_useAFreshSERVICEToken_andTheOrderId() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 3, "20.50");

        long orderId = json(checkout(userToken)).get("id").asLong();

        List<Request> reserves = reserveCalls();
        assertThat(reserves).hasSize(2);
        assertThat(reserves).allSatisfy(call -> assertThat(call.query()).endsWith("orderId=" + orderId));
        assertThat(skuOf(reserves.get(0))).isEqualTo("SKU-A");
        assertThat(reserves.get(0).query()).isEqualTo("quantity=2&orderId=" + orderId);
        assertThat(skuOf(reserves.get(1))).isEqualTo("SKU-B");
        assertThat(reserves.get(1).query()).isEqualTo("quantity=3&orderId=" + orderId);

        for (Request call : reserves) {
            // Not the caller's own token: the internal one, which a browser can never get.
            assertThat(call.header("Authorization")).isNotEqualTo(bearer(userToken));
            JWTClaimsSet claims = verifiedClaims(call.header("Authorization"));
            assertThat(claims.getSubject()).isEqualTo("order-service");
            assertThat(claims.getClaim("role")).isEqualTo("SERVICE");
            long lifetime = claims.getExpirationTime().getTime() / 1000 - claims.getIssueTime().getTime() / 1000;
            assertThat(lifetime).isEqualTo(120);
        }
    }

    @Test
    void theCallerOwnToken_goesToTheCart_forReadingAndForClearing() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken).andExpect(status().isCreated());

        List<Request> reads = shop.cartServer.requests("GET", "/api/cart");
        List<Request> clears = shop.cartServer.requests("DELETE", "/api/cart");
        assertThat(reads).hasSize(1);
        assertThat(reads.get(0).header("Authorization")).isEqualTo(bearer(userToken));
        assertThat(clears).hasSize(1);
        assertThat(clears.get(0).header("Authorization")).isEqualTo(bearer(userToken));
    }

    @Test
    void theOrder_isSavedAsPendingAndCommitted_beforeAnyStockIsReserved() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");
        List<String> seen = new CopyOnWriteArrayList<>();
        // This runs on the fake server's thread, with its own database connection: it only sees committed rows.
        shop.onReserve((sku, orderId) -> seen.add(sku + ":" + statusOf(orderId) + ":" + lineCount(orderId)));

        checkout(userToken).andExpect(status().isCreated());

        assertThat(seen).containsExactly("SKU-A:PENDING:2", "SKU-B:PENDING:2");
    }

    @Test
    void stockIsReservedInTheOrderOfTheCartLines() throws Exception {
        shop.addToCart(userId, "SKU-C", 1, "5.00");
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");

        checkout(userToken).andExpect(status().isCreated());

        List<String> skus = new ArrayList<>();
        reserveCalls().forEach(call -> skus.add(skuOf(call)));
        assertThat(skus).containsExactly("SKU-C", "SKU-A", "SKU-B");
    }

    @Test
    void secondCheckoutRightAfterASuccessfulOne_gives400YourCartIsEmpty_andNeverA500() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        checkout(userToken).andExpect(status().isCreated());

        checkout(userToken)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Your cart is empty."))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(orderCount(userId)).isEqualTo(1);
        assertThat(shop.stockOf("SKU-A")).isEqualTo(9);
    }

    @Test
    void emptyCart_gives400_savesNothing_andCallsNeitherProductNorStock() throws Exception {
        checkout(userToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Your cart is empty."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.productServer.requests()).isEmpty();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @Test
    void twoCartLinesWithTheSameSku_areMergedIntoOneOrderLine() throws Exception {
        shop.setCartReadBody("{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":2},{\"sku\":\"SKU-A\",\"quantity\":3}]}");

        JsonNode order = json(checkout(userToken).andExpect(status().isCreated()));

        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(reserveCalls()).hasSize(1);
        assertThat(reserveCalls().get(0).query()).startsWith("quantity=5&");
        assertThat(shop.stockOf("SKU-A")).isEqualTo(5);
    }

    @Test
    void anAdmin_canAlsoPlaceAnOrder() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(JwtTestSupport.adminToken(userId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void priceChange_afterAnOrder_doesNotChangeTheOldOrder_andANewOrderUsesTheNewPrice() throws Exception {
        long first = placeOrder(userId, "SKU-A", 1);
        shop.setPrice("SKU-A", "120.00");
        long second = placeOrder(userId, "SKU-A", 1);

        getOrder(userToken, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.total").value(100.00));
        getOrder(userToken, second).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(120.00))
                .andExpect(jsonPath("$.total").value(120.00));
    }

    @Test
    void aPriceWithMoreThanTwoDecimals_isRoundedToTwoDecimals() throws Exception {
        shop.productServer.on("GET", "/api/products/sku/SKU-C",
                request -> Reply.ok("{\"name\":\"Cable C\",\"price\":4.999}"));
        shop.addToCart(userId, "SKU-C", 3, "5.00");

        JsonNode order = json(checkout(userToken).andExpect(status().isCreated()));

        assertThat(order.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("15.00");
    }

    @Test
    void checkout_acceptsAnyRequestBody_andIgnoresIt() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"total\":0.01,\"items\":[{\"sku\":\"SKU-A\",\"unitPrice\":0.01}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(100.00));
    }
}
