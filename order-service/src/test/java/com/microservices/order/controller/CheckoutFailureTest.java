package com.microservices.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.order.support.CheckoutTestBase;
import com.microservices.order.support.FakeHttpServer.Reply;
import com.microservices.order.support.FakeShop.Behavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The failure outcomes of POST /api/orders (steps 1, 2 and 5 to 9 of the flow, Review Focus 3 and 6),
 * with fake services that fail, answer too late or are down.
 *
 * The rule behind all of them: stock and cart end up exactly as the contract says.
 */
class CheckoutFailureTest extends CheckoutTestBase {

    private static final String STOCK_DID_NOT_ANSWER = "The stock service did not answer. Please try again.";

    // ================================================================== steps 1 and 2: nothing is saved

    @ParameterizedTest
    @EnumSource(value = Behavior.class, names = {"ERROR_500", "DROP", "APPLY_THEN_LATE", "UNAUTHORIZED"})
    void cartServiceFails_gives503_andSavesNothing(Behavior behavior) throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setCartReadBehavior(behavior);

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The cart service did not answer. Please try again."))
                .andExpect(jsonPath("$.orderId").doesNotExist());

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.productServer.requests()).isEmpty();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @Test
    void cartServiceDown_gives503_andSavesNothing() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.cartServer.stop();

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The cart service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "this is not json",
            "{}",
            "{\"items\":null}",
            "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":0}]}",
            "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":-3}]}",
            "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1000}]}",
            "{\"items\":[{\"sku\":\"SKU-A\"}]}",
            "{\"items\":[{\"sku\":\"\",\"quantity\":1}]}",
            "{\"items\":[{\"quantity\":1}]}"
    })
    void aCartAnswerThatMakesNoSense_gives503_andSavesNothing(String cartBody) throws Exception {
        shop.setCartReadBody(cartBody);

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The cart service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @Test
    void productRemovedAfterItWasPutInTheCart_gives422_savesNothing_andLeavesTheCart() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");
        shop.removeProduct("SKU-B");

        checkout(userToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Product SKU-B is no longer available."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.inventoryServer.requests()).isEmpty();
        assertThat(shop.cartSize(userId)).isEqualTo(2);
        assertThat(shop.cartServer.requests("DELETE", "/api/cart")).isEmpty();
        assertThat(shop.stockOf("SKU-A")).isEqualTo(10);
    }

    @ParameterizedTest
    @EnumSource(value = Behavior.class, names = {"ERROR_500", "DROP", "APPLY_THEN_LATE", "UNAUTHORIZED"})
    void productServiceFails_gives503_andSavesNothing(Behavior behavior) throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setProductBehavior(behavior);

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @Test
    void productServiceDown_gives503_andSavesNothing() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.productServer.stop();

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"Phone A\"}",
            "{\"name\":\"Phone A\",\"price\":null}",
            "{\"name\":\"Phone A\",\"price\":-1.00}",
            "{\"name\":\"Phone A\",\"price\":\"cheap\"}",
            "{\"name\":\"Phone A\",\"price\":123456789012.00}",
            "{\"price\":10.00}",
            "{\"name\":\"  \",\"price\":10.00}",
            "not json"
    })
    void aProductAnswerThatMakesNoSense_gives503_andSavesNothing(String productBody) throws Exception {
        shop.productServer.on("GET", "/api/products/sku/SKU-A", request -> Reply.ok(productBody));
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    // ================================================================== step 6: stock refused

    @Test
    void stockRefusedForTheSecondOfThreeLines_releasesEveryLine_rejectsTheOrder_andAnswers409() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        shop.addToCart(userId, "SKU-C", 1, "5.00");

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Not enough stock for SKU-B: wanted 9, available 5")));
        long orderId = problem.get("orderId").asLong();

        // The order is kept as REJECTED, with its lines and the reason.
        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(rejectReasonOf(orderId)).isEqualTo("Not enough stock for SKU-B: wanted 9, available 5");
        assertThat(lineCount(orderId)).isEqualTo(3);

        // Reserving stops at the first refusal; release goes to EVERY line of the order.
        assertThat(reserveCalls()).extracting(call -> skuOf(call)).containsExactly("SKU-A", "SKU-B");
        assertThat(releaseCalls()).extracting(call -> skuOf(call)).containsExactlyInAnyOrder("SKU-A", "SKU-B", "SKU-C");
        assertThat(releaseCalls()).allSatisfy(call -> {
            assertThat(call.query()).isEqualTo("orderId=" + orderId);
            assertThat(verifiedClaims(call.header("Authorization")).getClaim("role")).isEqualTo("SERVICE");
        });

        // Stock is as before, and the cart was not touched.
        assertThat(shop.stockOf("SKU-A")).isEqualTo(10);
        assertThat(shop.stockOf("SKU-B")).isEqualTo(5);
        assertThat(shop.stockOf("SKU-C")).isEqualTo(8);
        assertThat(shop.activeReservations()).isZero();
        assertThat(shop.cartSize(userId)).isEqualTo(3);
        assertThat(shop.cartServer.requests("DELETE", "/api/cart")).isEmpty();
    }

    @Test
    void stockRefusedForTheFirstLine_alsoReleasesTheOtherLines() throws Exception {
        shop.addToCart(userId, "SKU-A", 99, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");

        JsonNode problem = json(checkout(userToken).andExpect(status().isConflict()));

        assertThat(reserveCalls()).hasSize(1);
        assertThat(releaseCalls()).extracting(call -> skuOf(call)).containsExactlyInAnyOrder("SKU-A", "SKU-B");
        assertThat(statusOf(problem.get("orderId").asLong())).isEqualTo("REJECTED");
        assertThat(shop.stockOf("SKU-B")).isEqualTo(5);
    }

    @Test
    void inventoryHasNoRecordForTheSku_404_rejectsTheOrderWithAPlainReason() throws Exception {
        shop.addProduct("SKU-NOSTOCK", "No stock thing", "9.99");
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-NOSTOCK", 1, "9.99");

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No stock record for SKU SKU-NOSTOCK")));
        long orderId = problem.get("orderId").asLong();

        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(rejectReasonOf(orderId)).isEqualTo("No stock record for SKU SKU-NOSTOCK");
        assertThat(releaseCalls()).extracting(call -> skuOf(call)).containsExactlyInAnyOrder("SKU-A", "SKU-NOSTOCK");
        assertThat(shop.stockOf("SKU-A")).isEqualTo(10);
        assertThat(shop.cartSize(userId)).isEqualTo(2);
    }

    // ================================================================== step 7: stock service fails

    @ParameterizedTest
    @EnumSource(value = Behavior.class,
            names = {"ERROR_500", "DROP", "APPLY_THEN_LATE", "APPLY_THEN_DROP", "UNAUTHORIZED"})
    void inventoryFailsOnTheSecondLine_gives503_rejectsTheOrder_releasesEveryLine_andStockIsAsBefore(
            Behavior behavior) throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 3, "20.50");
        shop.addToCart(userId, "SKU-C", 1, "5.00");
        // For APPLY_THEN_LATE and APPLY_THEN_DROP the stock service really reserved SKU-B,
        // but the order service never saw the answer. The release must give it back.
        shop.setReserveBehavior("SKU-B", behavior);

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value(STOCK_DID_NOT_ANSWER)));
        long orderId = problem.get("orderId").asLong();

        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(rejectReasonOf(orderId)).isEqualTo(STOCK_DID_NOT_ANSWER);
        assertThat(reserveCalls()).extracting(call -> skuOf(call)).containsExactly("SKU-A", "SKU-B");
        // Every line, including the one that failed and the one never tried.
        assertThat(releaseCalls()).extracting(call -> skuOf(call)).containsExactlyInAnyOrder("SKU-A", "SKU-B", "SKU-C");
        assertThat(shop.stockOf("SKU-A")).isEqualTo(10);
        assertThat(shop.stockOf("SKU-B")).isEqualTo(5);
        assertThat(shop.stockOf("SKU-C")).isEqualTo(8);
        assertThat(shop.activeReservations()).isZero();
        assertThat(shop.cartSize(userId)).isEqualTo(3);
        assertThat(shop.cartServer.requests("DELETE", "/api/cart")).isEmpty();
    }

    @Test
    void inventoryAnswersWithAnUnexpectedStatus_isTreatedAsAServiceFailure() throws Exception {
        shop.inventoryServer.on("POST", "/api/inventory/sku/SKU-A/reserve",
                request -> Reply.json(400, "{\"title\":\"Bad Request\",\"status\":400}"));
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(STOCK_DID_NOT_ANSWER)));

        assertThat(statusOf(problem.get("orderId").asLong())).isEqualTo("REJECTED");
        assertThat(releaseCalls()).hasSize(1);
    }

    @Test
    void inventoryDown_gives503_rejectsTheOrder_andNeverAnswers500() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");
        shop.inventoryServer.stop();

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(STOCK_DID_NOT_ANSWER)));
        long orderId = problem.get("orderId").asLong();

        // The releases could not get through either: that is logged and ignored, the order is still REJECTED.
        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(rejectReasonOf(orderId)).isEqualTo(STOCK_DID_NOT_ANSWER);
        assertThat(shop.cartSize(userId)).isEqualTo(2);
        assertThat(shop.cartServer.requests("DELETE", "/api/cart")).isEmpty();
        assertThat(shop.stockOf("SKU-A")).isEqualTo(10);
    }

    @Test
    void aSlowInventory_isCutOffAtTheTimeout_andTheCheckoutEndsInAboutASecond() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setReserveBehavior("SKU-A", Behavior.APPLY_THEN_LATE);

        long started = System.nanoTime();
        checkout(userToken).andExpect(status().isServiceUnavailable());
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        // The test time-out is 0.6 s; the fake would have answered after 2.5 s.
        assertThat(seconds).isLessThan(2);
    }

    // ================================================================== step 8: a failing release

    @ParameterizedTest
    @EnumSource(value = Behavior.class, names = {"ERROR_500", "DROP", "APPLY_THEN_LATE"})
    void aFailingRelease_isIgnored_theOrderStaysRejected_andTheOtherLinesAreStillReleased(Behavior behavior)
            throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        shop.addToCart(userId, "SKU-C", 1, "5.00");
        shop.setReleaseBehavior("SKU-A", behavior);

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough stock for SKU-B: wanted 9, available 5")));

        assertThat(statusOf(problem.get("orderId").asLong())).isEqualTo("REJECTED");
        // The release of SKU-A failed, but the releases for SKU-B and SKU-C were still sent.
        assertThat(releaseCalls()).extracting(call -> skuOf(call)).containsExactlyInAnyOrder("SKU-A", "SKU-B", "SKU-C");
        // The reservation of SKU-A is still stored, so a clean-up job can release it later by order id.
        if (behavior == Behavior.APPLY_THEN_LATE) {
            assertThat(shop.stockOf("SKU-A")).isEqualTo(10);
        } else {
            assertThat(shop.stockOf("SKU-A")).isEqualTo(8);
            assertThat(shop.activeReservations()).isEqualTo(1);
        }
        assertThat(shop.stockOf("SKU-B")).isEqualTo(5);
    }

    @Test
    void aFailingRelease_afterAServiceFailure_isIgnoredToo() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");
        shop.setReserveBehavior("SKU-B", Behavior.DROP);
        shop.setReleaseBehavior("SKU-A", Behavior.ERROR_500);
        shop.setReleaseBehavior("SKU-B", Behavior.DROP);

        JsonNode problem = json(checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(STOCK_DID_NOT_ANSWER)));

        assertThat(statusOf(problem.get("orderId").asLong())).isEqualTo("REJECTED");
        assertThat(releaseCalls()).hasSize(2);
    }

    // ================================================================== step 9: cart clean-up fails

    @ParameterizedTest
    @EnumSource(value = Behavior.class, names = {"ERROR_500", "DROP", "APPLY_THEN_LATE", "UNAUTHORIZED"})
    void cartCleanUpFails_theOrderStaysConfirmed_answers201_withCartClearedFalse(Behavior behavior)
            throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 3, "20.50");
        shop.setCartClearBehavior(behavior);

        JsonNode order = json(checkout(userToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cartCleared").value(false))
                .andExpect(jsonPath("$.total").value(261.50)));
        long orderId = order.get("id").asLong();

        assertThat(statusOf(orderId)).isEqualTo("CONFIRMED");
        // The stock stays reserved: the order is real.
        assertThat(shop.stockOf("SKU-A")).isEqualTo(8);
        assertThat(shop.stockOf("SKU-B")).isEqualTo(2);
        assertThat(releaseCalls()).isEmpty();
        // The cart really was not emptied (except when the fake did the work and only answered too late).
        if (behavior != Behavior.APPLY_THEN_LATE) {
            assertThat(shop.cartSize(userId)).isEqualTo(2);
        }
    }

    @Test
    void cartServiceGoesDownBeforeTheCleanUp_theOrderStaysConfirmed() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        // The cart answers the first read, then it goes down (on the first stock call).
        shop.onReserve((sku, orderId) -> shop.cartServer.stop());

        checkout(userToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cartCleared").value(false));

        assertThat(shop.stockOf("SKU-A")).isEqualTo(9);
    }

    // ================================================================== odd answers that must not become a 500

    @Test
    void aCartSkuLongerThanTheColumn_gives503_andSavesNothing() throws Exception {
        shop.setCartReadBody("{\"items\":[{\"sku\":\"" + "X".repeat(51) + "\",\"quantity\":1}]}");

        checkout(userToken).andExpect(status().isServiceUnavailable());

        assertThat(orderCount(userId)).isZero();
    }

    @Test
    void aCartThatSendsTheSameSkuSoOftenThatItAddsUpToTooMuch_gives503_andSavesNothing() throws Exception {
        shop.setCartReadBody("{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":600},"
                + "{\"sku\":\"SKU-A\",\"quantity\":600}]}");

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The cart service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @Test
    void aVeryLongReasonFromInventory_isCutToFitTheColumn_andTheOrderIsStillRejected() throws Exception {
        shop.inventoryServer.on("POST", "/api/inventory/sku/SKU-A/reserve",
                request -> Reply.json(409, "{\"detail\":\"" + "x".repeat(900) + "\"}"));
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        JsonNode problem = json(checkout(userToken).andExpect(status().isConflict()));

        assertThat(problem.get("detail").asText()).hasSize(500);
        assertThat(rejectReasonOf(problem.get("orderId").asLong())).hasSize(500);
    }

    @Test
    void aConflictWithoutAReadableReason_getsAPlainSentence() throws Exception {
        shop.inventoryServer.on("POST", "/api/inventory/sku/SKU-A/reserve",
                request -> Reply.json(409, "this is not json"));
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough stock for SKU-A"));
    }

    @Test
    void pricesSoHighThatTheTotalCannotBeStored_give503_andSaveNothing() throws Exception {
        shop.setPrice("SKU-A", "99999999.99");
        shop.setPrice("SKU-B", "99999999.99");
        shop.addToCart(userId, "SKU-A", 99, "1.00");
        shop.addToCart(userId, "SKU-B", 99, "1.00");

        checkout(userToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The product service did not answer. Please try again."));

        assertThat(orderCount(userId)).isZero();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }
}
