package com.microservices.cart.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.support.AbstractCartApiTest;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests what the cart does for a logged-in person: show, add, replace, remove, clear.
 * product-service is a fake HTTP server (see FakeProductService).
 */
class CartApiTest extends AbstractCartApiTest {

    private long userId;
    private String token;

    @BeforeEach
    void newUserAndProducts() {
        userId = TestTokens.newUserId();
        token = TestTokens.user(userId);
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "999.99");
        PRODUCTS.addProduct("CASE-1", "Phone Case", "10.5");
        PRODUCTS.addProduct("CABLE-1", "USB Cable", "0.10");
    }

    // ---------------------------------------------------------------- GET /api/cart

    @Test
    void emptyCart_hasNoItems_countZero_totalZero() throws Exception {
        getCart(token)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.total").value(0.0));
        assertThat(PRODUCTS.requests()).as("an empty cart needs no product lookup").isEmpty();
    }

    @Test
    void getCart_showsNamePriceLineTotal_itemCountAndTotal() throws Exception {
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        setQuantity(token, "CASE-1", 3).andExpect(status().isOk());

        JsonNode cart = json(getCart(token).andExpect(status().isOk()));

        assertThat(cart.get("items")).hasSize(2);
        JsonNode phone = cart.get("items").get(0);
        assertThat(phone.get("sku").asText()).isEqualTo("PHONE-1");
        assertThat(phone.get("quantity").asInt()).isEqualTo(2);
        assertThat(phone.get("available").asBoolean()).isTrue();
        assertThat(phone.get("name").asText()).isEqualTo("Test Phone");
        assertThat(phone.get("unitPrice").decimalValue()).isEqualByComparingTo("999.99");
        assertThat(phone.get("lineTotal").decimalValue()).isEqualByComparingTo("1999.98");

        JsonNode phoneCase = cart.get("items").get(1);
        assertThat(phoneCase.get("sku").asText()).isEqualTo("CASE-1");
        assertThat(phoneCase.get("unitPrice").decimalValue()).isEqualByComparingTo("10.50");
        assertThat(phoneCase.get("lineTotal").decimalValue()).isEqualByComparingTo("31.50");

        assertThat(cart.get("itemCount").asInt()).as("sum of the quantities").isEqualTo(5);
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("2031.48");
    }

    @Test
    void moneyHasTwoDecimals_inTheJson() throws Exception {
        setQuantity(token, "CASE-1", 2).andExpect(status().isOk());

        String body = getCart(token).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"unitPrice\":10.50").contains("\"lineTotal\":21.00").contains("\"total\":21.00");
    }

    @Test
    void smallPrices_addUpExactly() throws Exception {
        setQuantity(token, "CABLE-1", 3).andExpect(status().isOk());

        JsonNode cart = json(getCart(token));

        assertThat(cart.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("0.30");
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("0.30");
    }

    @Test
    void itemsAreInTheOrderTheyWereAdded_andReplacingKeepsThePlace() throws Exception {
        setQuantity(token, "CASE-1", 1).andExpect(status().isOk());
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        setQuantity(token, "CABLE-1", 1).andExpect(status().isOk());
        setQuantity(token, "CASE-1", 7).andExpect(status().isOk());

        JsonNode items = json(getCart(token)).get("items");

        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("sku").asText()).isEqualTo("CASE-1");
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(7);
        assertThat(items.get(1).get("sku").asText()).isEqualTo("PHONE-1");
        assertThat(items.get(2).get("sku").asText()).isEqualTo("CABLE-1");
    }

    @Test
    void priceComesFromProductServiceEachTime_notFromTheCart() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        PRODUCTS.addProduct("PHONE-1", "Test Phone v2", "899.00");

        JsonNode cart = json(getCart(token));

        assertThat(cart.get("items").get(0).get("name").asText()).isEqualTo("Test Phone v2");
        assertThat(cart.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("899.00");
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("899.00");
    }

    // ---------------------------------------------------------------- PUT /api/cart/items/{sku}

    @Test
    void put_addsAnItem_andAnswersWithTheWholeCart() throws Exception {
        setQuantity(token, "PHONE-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("PHONE-1"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].name").value("Test Phone"))
                .andExpect(jsonPath("$.itemCount").value(2));

        JsonNode answer = json(setQuantity(token, "CASE-1", 1).andExpect(status().isOk()));
        assertThat(answer.get("items")).hasSize(2);
        assertThat(answer.get("itemCount").asInt()).isEqualTo(3);
        assertThat(answer.get("total").decimalValue()).isEqualByComparingTo("2010.48");
    }

    @Test
    void put_answerIsTheSameAsGet() throws Exception {
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        JsonNode fromPut = json(setQuantity(token, "CASE-1", 4));
        JsonNode fromGet = json(getCart(token));

        assertThat(fromPut).isEqualTo(fromGet);
    }

    @Test
    void put_setsTheQuantity_itDoesNotAddToIt() throws Exception {
        setQuantity(token, "PHONE-1", 5).andExpect(status().isOk());

        setQuantity(token, "PHONE-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.itemCount").value(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99})
    void put_acceptsTheLimits(int quantity) throws Exception {
        setQuantity(token, "PHONE-1", quantity)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(quantity));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"quantity\":0}",
            "{\"quantity\":100}",
            "{\"quantity\":-1}",
            "{\"quantity\":-2147483648}",
            "{\"quantity\":2147483647}",
            // does not fit into an int
            "{\"quantity\":2147483648}",
            "{\"quantity\":99999999999999999999}",
            "{\"quantity\":-99999999999999999999}",
            // not a whole number, or not a number at all
            "{\"quantity\":1.5}",
            "{\"quantity\":2.0}",
            "{\"quantity\":1e2}",
            "{\"quantity\":\"abc\"}",
            "{\"quantity\":\"\"}",
            "{\"quantity\":\"5\"}",
            "{\"quantity\":true}",
            "{\"quantity\":[2]}",
            "{\"quantity\":{\"value\":2}}",
            // missing
            "{\"quantity\":null}",
            "{}",
            "{\"qty\":2}",
            "[]",
            "[2]",
            "2",
            "\"2\"",
            "null",
            "",
            "{",
            "not json"
    })
    void put_badQuantity_gives400_andChangesNothing(String body) throws Exception {
        setQuantity(token, "CASE-1", 4).andExpect(status().isOk());

        putQuantity(token, "PHONE-1", body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        JsonNode cart = json(getCart(token));
        assertThat(cart.get("items")).as("the cart is unchanged").hasSize(1);
        assertThat(cart.get("items").get(0).get("sku").asText()).isEqualTo("CASE-1");
        assertThat(cart.get("items").get(0).get("quantity").asInt()).isEqualTo(4);
    }

    @Test
    void put_badQuantity_toAnExistingItem_keepsItsOldQuantity() throws Exception {
        setQuantity(token, "PHONE-1", 3).andExpect(status().isOk());

        setQuantity(token, "PHONE-1", 0).andExpect(status().isBadRequest());
        setQuantity(token, "PHONE-1", 100).andExpect(status().isBadRequest());

        assertThat(json(getCart(token)).get("items").get(0).get("quantity").asInt()).isEqualTo(3);
    }

    @Test
    void put_badQuantityForAnUnknownSku_is400_notAProductLookup() throws Exception {
        setQuantity(token, "NO-SUCH-SKU", 0).andExpect(status().isBadRequest());

        assertThat(PRODUCTS.requests()).as("the body is checked before product-service is asked").isEmpty();
    }

    @Test
    void put_withoutAJsonContentType_isRefusedWithA4xx_notA500() throws Exception {
        ResultActions result = mockMvc.perform(put("/api/cart/items/PHONE-1")
                .header("Authorization", TestTokens.bearer(token))
                .contentType(MediaType.TEXT_PLAIN)
                .content("{\"quantity\":2}"));

        result.andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void put_unknownSku_gives422_andSavesNothing() throws Exception {
        setQuantity(token, "NO-SUCH-SKU", 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Unknown product: NO-SUCH-SKU"));

        getCart(token).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void put_aSkuThatIsTooLongOrBlank_gives422_withoutAskingProductService() throws Exception {
        String tooLong = "A".repeat(51);

        setQuantity(token, tooLong, 1).andExpect(status().isUnprocessableEntity());
        setQuantity(token, "   ", 1).andExpect(status().isUnprocessableEntity());

        assertThat(PRODUCTS.requests()).isEmpty();
        getCart(token).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void put_aSkuOfExactly50Characters_isFine() throws Exception {
        String longest = "L".repeat(50);
        PRODUCTS.addProduct(longest, "Long SKU product", "1.00");

        setQuantity(token, longest, 1).andExpect(status().isOk());
    }

    @Test
    void put_skuWithSpecialCharacters_isSentToProductServiceEncoded() throws Exception {
        String odd = "A B&C?D#E";
        PRODUCTS.addProduct(odd, "Odd product", "5.00");

        setQuantity(token, odd, 1).andExpect(status().isOk());

        assertThat(PRODUCTS.requests()).hasSize(1);
        String rawPath = PRODUCTS.requests().get(0).rawPath();
        assertThat(rawPath).startsWith("/api/products/sku/").doesNotContain(" ", "?", "#");
        assertThat(rawPath).contains("%20", "%3F", "%23");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A%25B", "A%2FB", "A%5CB", "A;B", "A%3BB", "..", "%2E%2E", "A%00B"})
    void put_skuTheFirewallDoesNotAllow_isA4xx_neverA500_andNeverReachesProductService(String rawSku)
            throws Exception {
        int status = mockMvc.perform(put(java.net.URI.create("/api/cart/items/" + rawSku))
                        .header("Authorization", TestTokens.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isBetween(400, 499);
        assertThat(PRODUCTS.requests()).isEmpty();
    }

    @Test
    void productService_isOnlyAskedWithGet_andNeverGetsTheUsersToken() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        getCart(token).andExpect(status().isOk());

        assertThat(PRODUCTS.requests()).isNotEmpty();
        assertThat(PRODUCTS.requests()).allSatisfy(received -> {
            assertThat(received.method()).isEqualTo("GET");
            assertThat(received.authorization()).as("the login token must not be passed on").isNull();
        });
    }

    @Test
    void put_whenProductServiceFails_gives503_andSavesNothing() throws Exception {
        PRODUCTS.failWithStatus(500);

        setQuantity(token, "PHONE-1", 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));

        PRODUCTS.failWithStatus(0);
        getCart(token).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void put_whenProductServiceFailsAfterTheCheck_stillSavesNothing() throws Exception {
        // PHONE-1 is in the cart. Adding CASE-1 needs the lookup of both. If the answer for the cart
        // list fails, the new item must not be saved.
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        PRODUCTS.removeProduct("PHONE-1");
        PRODUCTS.answerWithRawBody("PHONE-1", "this is not json");

        setQuantity(token, "CASE-1", 1).andExpect(status().isServiceUnavailable());

        PRODUCTS.removeProduct("PHONE-1");
        JsonNode cart = json(getCart(token));
        assertThat(cart.get("items")).as("only PHONE-1, which is now unavailable").hasSize(1);
        assertThat(cart.get("items").get(0).get("sku").asText()).isEqualTo("PHONE-1");
    }

    // ---------------------------------------------------------------- available:false

    @Test
    void aProductThatNoLongerExists_isShownAsUnavailable_withNullNameAndPrices() throws Exception {
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        setQuantity(token, "CASE-1", 1).andExpect(status().isOk());
        PRODUCTS.removeProduct("PHONE-1");

        String body = getCart(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode cart = objectMapper.readTree(body);

        JsonNode gone = cart.get("items").get(0);
        assertThat(gone.get("sku").asText()).isEqualTo("PHONE-1");
        assertThat(gone.get("quantity").asInt()).isEqualTo(2);
        assertThat(gone.get("available").asBoolean()).isFalse();
        // the fields are there, with null (the contract says "string|null" and "number|null")
        assertThat(gone.has("name")).isTrue();
        assertThat(gone.get("name").isNull()).isTrue();
        assertThat(gone.get("unitPrice").isNull()).isTrue();
        assertThat(gone.get("lineTotal").isNull()).isTrue();

        JsonNode other = cart.get("items").get(1);
        assertThat(other.get("available").asBoolean()).isTrue();

        assertThat(cart.get("itemCount").asInt()).as("all quantities count").isEqualTo(3);
        assertThat(cart.get("total").decimalValue()).as("only available items are added up")
                .isEqualByComparingTo("10.50");
    }

    @Test
    void anUnavailableItem_canStillBeChanged_ifTheProductExistsAgain_andRemoved() throws Exception {
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        PRODUCTS.removeProduct("PHONE-1");

        // replacing the quantity needs the product, so it is refused now
        setQuantity(token, "PHONE-1", 1).andExpect(status().isUnprocessableEntity());
        // removing never needs product-service
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        getCart(token).andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ---------------------------------------------------------------- GET when product-service is not there

    @Test
    void get_whenProductServiceFails_gives503() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        PRODUCTS.failWithStatus(503);

        getCart(token)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));
    }

    @Test
    void get_aProductAnswerWeCannotUse_is503_notAnUnavailableItem() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        PRODUCTS.answerWithRawBody("PHONE-1", "{\"name\":\"No price here\"}");

        getCart(token).andExpect(status().isServiceUnavailable());

        PRODUCTS.answerWithRawBody("PHONE-1", "this is not json");
        getCart(token).andExpect(status().isServiceUnavailable());

        PRODUCTS.answerWithRawBody("PHONE-1", "");
        getCart(token).andExpect(status().isServiceUnavailable());
    }

    @Test
    void get_anEmptyCart_worksEvenWhenProductServiceIsDown() throws Exception {
        PRODUCTS.failWithStatus(500);

        getCart(token).andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(0));
    }

    // ---------------------------------------------------------------- DELETE

    @Test
    void deleteItem_removesOnlyThatItem_andAnswers204() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        setQuantity(token, "CASE-1", 2).andExpect(status().isOk());

        removeItem(token, "PHONE-1").andExpect(status().isNoContent()).andExpect(content().string(""));

        JsonNode cart = json(getCart(token));
        assertThat(cart.get("items")).hasSize(1);
        assertThat(cart.get("items").get(0).get("sku").asText()).isEqualTo("CASE-1");
        assertThat(cart.get("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void deleteItem_thatIsNotInTheCart_isStill204() throws Exception {
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());

        setQuantity(token, "CASE-1", 1).andExpect(status().isOk());
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        removeItem(token, "NEVER-HEARD-OF-IT").andExpect(status().isNoContent());
        getCart(token).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void deleteItem_twice_isStill204() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());

        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
    }

    @Test
    void deleteItem_doesNotNeedProductService() throws Exception {
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        PRODUCTS.failWithStatus(500);

        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        clearCart(token).andExpect(status().isNoContent());
    }

    @Test
    void deleteCart_emptiesTheCart_andAnswers204_alsoWhenItIsAlreadyEmpty() throws Exception {
        clearCart(token).andExpect(status().isNoContent());

        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        setQuantity(token, "CASE-1", 2).andExpect(status().isOk());
        clearCart(token).andExpect(status().isNoContent()).andExpect(content().string(""));

        getCart(token).andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.itemCount").value(0));
        clearCart(token).andExpect(status().isNoContent());
    }

    @Test
    void anItemCanBeAddedAgainAfterItWasRemoved() throws Exception {
        setQuantity(token, "PHONE-1", 4).andExpect(status().isOk());
        removeItem(token, "PHONE-1").andExpect(status().isNoContent());

        setQuantity(token, "PHONE-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
    }

    // ---------------------------------------------------------------- one cart per person

    @Test
    void everyUserHasAnOwnCart() throws Exception {
        String otherToken = TestTokens.user(TestTokens.newUserId());
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        setQuantity(otherToken, "CASE-1", 5).andExpect(status().isOk());

        JsonNode mine = json(getCart(token));
        JsonNode theirs = json(getCart(otherToken));

        assertThat(mine.get("items")).hasSize(1);
        assertThat(mine.get("items").get(0).get("sku").asText()).isEqualTo("PHONE-1");
        assertThat(theirs.get("items")).hasSize(1);
        assertThat(theirs.get("items").get(0).get("sku").asText()).isEqualTo("CASE-1");
        assertThat(theirs.get("itemCount").asInt()).isEqualTo(5);
    }

    @Test
    void twoUsersCanHaveTheSameSkuWithDifferentQuantities() throws Exception {
        String otherToken = TestTokens.user(TestTokens.newUserId());

        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        setQuantity(otherToken, "PHONE-1", 9).andExpect(status().isOk());

        assertThat(json(getCart(token)).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(json(getCart(otherToken)).get("items").get(0).get("quantity").asInt()).isEqualTo(9);
    }

    @Test
    void removingAndClearing_neverTouchesAnotherUsersCart() throws Exception {
        String otherToken = TestTokens.user(TestTokens.newUserId());
        setQuantity(token, "PHONE-1", 2).andExpect(status().isOk());
        setQuantity(otherToken, "PHONE-1", 3).andExpect(status().isOk());
        setQuantity(otherToken, "CASE-1", 1).andExpect(status().isOk());

        removeItem(token, "PHONE-1").andExpect(status().isNoContent());
        getCart(otherToken).andExpect(jsonPath("$.items", hasSize(2)));

        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        clearCart(token).andExpect(status().isNoContent());
        getCart(otherToken).andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.itemCount").value(4));
    }

    @Test
    void anAdminHasAnOwnCartLikeEveryoneElse() throws Exception {
        long adminId = TestTokens.newUserId();
        String adminToken = TestTokens.admin(adminId);

        setQuantity(adminToken, "PHONE-1", 1).andExpect(status().isOk());

        getCart(adminToken).andExpect(jsonPath("$.items", hasSize(1)));
        getCart(token).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void theCartIsKeyedByTheUserIdInSub_notByTheEmailOrTheRole() throws Exception {
        // The same person (same sub) with a USER token and an ADMIN token sees the same cart.
        setQuantity(TestTokens.user(userId), "PHONE-1", 1).andExpect(status().isOk());

        getCart(TestTokens.admin(userId)).andExpect(jsonPath("$.items", hasSize(1)));
    }

    // ---------------------------------------------------------------- parallel requests

    @Test
    void parallelPutsOfTheSameItem_allSucceed_andLeaveOneLine() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int quantity = (i % 99) + 1;
                calls.add(() -> setQuantity(token, "PHONE-1", quantity).andReturn().getResponse().getStatus());
            }
            List<Future<Integer>> results = pool.invokeAll(calls);
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        JsonNode cart = json(getCart(token));
        assertThat(cart.get("items")).as("one line only (the table has a unique key)").hasSize(1);
        assertThat(cart.get("items").get(0).get("quantity").asInt()).isBetween(1, 99);
    }

    @Test
    void moneyTotal_usesBigDecimalMath() throws Exception {
        PRODUCTS.addProduct("PENNY-1", "Penny item", "0.01");
        setQuantity(token, "PENNY-1", 99).andExpect(status().isOk());
        setQuantity(token, "CABLE-1", 99).andExpect(status().isOk());

        JsonNode cart = json(getCart(token));

        assertThat(cart.get("total").decimalValue()).isEqualTo(new BigDecimal("10.89"));
    }

    @Test
    void aHugePrice_doesNotBreakTheTotal() throws Exception {
        PRODUCTS.addProduct("BIG-1", "Very expensive", "9999999999.99");
        setQuantity(token, "BIG-1", 99).andExpect(status().isOk());

        JsonNode cart = json(getCart(token));

        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("989999999999.01");
    }

    @Test
    void aProductNameWithQuotesAndUnicode_comesBackIntact() throws Exception {
        PRODUCTS.addProduct("ODD-NAME", "Caf\\u00e9 \\\"Deluxe\\\" mug", "4.00");

        setQuantity(token, "ODD-NAME", 1).andExpect(status().isOk());

        assertThat(json(getCart(token)).get("items").get(0).get("name").asText())
                .isEqualTo("Café \"Deluxe\" mug");
    }
}
