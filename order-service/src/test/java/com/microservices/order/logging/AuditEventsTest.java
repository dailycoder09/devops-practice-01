package com.microservices.order.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.order.support.FakeHttpServer.Reply;
import com.microservices.order.support.FakeShop.Behavior;
import com.microservices.order.support.LogCapture.Line;
import com.microservices.order.support.LoggingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 7 of the logging contract: the audit events of order-service, each once per action, with the right level,
 * the right fields and values, and the user and trace from the MDC:
 * order_checkout_started, order_confirmed, order_rejected, release_failed, cart_clear_failed, service_token_issued.
 */
class AuditEventsTest extends LoggingTestBase {

    @Autowired
    private com.microservices.order.service.ServiceTokenIssuer tokenIssuer;

    private static final List<String> ALL_ORDER_EVENTS = List.of("order_checkout_started", "order_confirmed",
            "order_rejected", "release_failed", "cart_clear_failed");

    private void assertNoEventsExcept(String... allowed) {
        List<String> allowedNames = List.of(allowed);
        for (String event : ALL_ORDER_EVENTS) {
            if (!allowedNames.contains(event)) {
                assertThat(logs.events(event)).as("event " + event).isEmpty();
            }
        }
    }

    // ================================================================== a successful checkout

    @Test
    void aConfirmedCheckout_logsStartedAndConfirmed_onceEach_withTheirFields() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "90.00");
        shop.addToCart(userId, "SKU-B", 3, "20.50");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();
        JsonNode order = objectMapper.readTree(result.getResponse().getContentAsString());

        Line started = logs.single("order_checkout_started");
        assertThat(started.level()).isEqualTo("INFO");
        assertThat(started.logger()).isEqualTo("audit");
        assertThat(started.field("itemCount")).isEqualTo(2);
        assertThat(started.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(started.mdc("userEmail")).isEqualTo("user" + userId + "@example.com");
        assertThat(started.mdc("userRole")).isEqualTo("USER");
        assertThat(started.mdc("traceId")).isEqualTo(traceIdHeader(result));

        Line confirmed = logs.single("order_confirmed");
        assertThat(confirmed.level()).isEqualTo("INFO");
        assertThat(confirmed.logger()).isEqualTo("audit");
        assertThat(confirmed.field("orderId")).isEqualTo(order.get("id").asInt());
        assertThat(new BigDecimal(String.valueOf(confirmed.field("total")))).isEqualByComparingTo("261.50");
        assertThat(confirmed.field("itemCount")).isEqualTo(2);
        assertThat(confirmed.field("cartCleared")).isEqualTo(true);
        assertThat(confirmed.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(confirmed.mdc("traceId")).isEqualTo(traceIdHeader(result));

        assertNoEventsExcept("order_checkout_started", "order_confirmed");
        assertThat(logs.lines().indexOf(started)).isLessThan(logs.lines().indexOf(confirmed));
    }

    @Test
    void theEventsCarryNoPersonalDataBeyondTheMdc_noEmailAndNoItemsAsFields() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken).andExpect(status().isCreated());

        for (String event : List.of("order_checkout_started", "order_confirmed")) {
            assertThat(logs.single(event).fields().keySet()).doesNotContain("email", "userEmail", "token", "authorization");
        }
        assertThat(logs.single("order_confirmed").fields().keySet())
                .containsExactlyInAnyOrder("event", "orderId", "total", "itemCount", "cartCleared");
        assertThat(logs.single("order_checkout_started").fields().keySet())
                .containsExactlyInAnyOrder("event", "itemCount");
    }

    @Test
    void anOrderWithTheSameSkuTwiceInTheCart_countsTheMergedLines() throws Exception {
        shop.setCartReadBody("{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1},{\"sku\":\"SKU-A\",\"quantity\":2},"
                + "{\"sku\":\"SKU-B\",\"quantity\":1}]}");

        checkout(userToken).andExpect(status().isCreated());

        assertThat(logs.single("order_checkout_started").field("itemCount")).isEqualTo(2);
        assertThat(logs.single("order_confirmed").field("itemCount")).isEqualTo(2);
    }

    // ================================================================== the checkout stops before an order exists

    @Test
    void anEmptyCart_logsNoOrderEvent() throws Exception {
        checkout(userToken).andExpect(status().isBadRequest());

        assertNoEventsExcept();
    }

    @Test
    void aProductThatIsGone_422_startedButNoOrderWasSaved_soNoRejectedEvent() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.removeProduct("SKU-A");

        checkout(userToken).andExpect(status().isUnprocessableEntity());

        assertThat(logs.single("order_checkout_started").field("itemCount")).isEqualTo(1);
        assertNoEventsExcept("order_checkout_started");
    }

    @Test
    void theCartServiceDown_503_logsNoOrderEvent() throws Exception {
        shop.setCartReadBehavior(Behavior.ERROR_500);

        checkout(userToken).andExpect(status().isServiceUnavailable());

        assertNoEventsExcept();
    }

    // ================================================================== cart clean-up

    @Test
    void aFailedCartCleanUp_logsCartClearFailedAtWarn_andTheOrderIsConfirmedWithCartClearedFalse() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setCartClearBehavior(Behavior.ERROR_500);

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();
        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        Line failed = logs.single("cart_clear_failed");
        assertThat(failed.level()).isEqualTo("WARN");
        assertThat(failed.logger()).isEqualTo("audit");
        assertThat(((Number) failed.field("orderId")).longValue()).isEqualTo(orderId);
        assertThat(failed.fields().keySet()).containsExactlyInAnyOrder("event", "orderId");
        assertThat(logs.single("order_confirmed").field("cartCleared")).isEqualTo(false);
        assertNoEventsExcept("order_checkout_started", "order_confirmed", "cart_clear_failed");
    }

    @Test
    void aCartCleanUpThatDropsTheConnection_isTheSame() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setCartClearBehavior(Behavior.DROP);

        checkout(userToken).andExpect(status().isCreated());

        assertThat(logs.single("cart_clear_failed").level()).isEqualTo("WARN");
    }

    // ================================================================== rejected orders

    @Test
    void stockRefused_409_logsOrderRejectedAtWarn_withTheReason_andNoConfirmedEvent() throws Exception {
        shop.addToCart(userId, "SKU-B", 9, "20.50");

        MvcResult result = checkout(userToken).andExpect(status().isConflict()).andReturn();
        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("orderId").asLong();

        Line rejected = logs.single("order_rejected");
        assertThat(rejected.level()).isEqualTo("WARN");
        assertThat(rejected.logger()).isEqualTo("audit");
        assertThat(((Number) rejected.field("orderId")).longValue()).isEqualTo(orderId);
        assertThat(rejected.field("reason")).isEqualTo("Not enough stock for SKU-B: wanted 9, available 5");
        assertThat(rejected.fields().keySet()).containsExactlyInAnyOrder("event", "orderId", "reason");
        assertThat(rejected.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(rejected.mdc("traceId")).isEqualTo(traceIdHeader(result));
        assertNoEventsExcept("order_checkout_started", "order_rejected");
    }

    @Test
    void aSkuWithoutAStockRecord_404_isRejectedWithThatReason() throws Exception {
        shop.addProduct("SKU-N", "No stock record", "3.00");
        shop.addToCart(userId, "SKU-N", 1, "3.00");

        checkout(userToken).andExpect(status().isConflict());

        assertThat(logs.single("order_rejected").field("reason")).isEqualTo("No stock record for SKU SKU-N");
    }

    @Test
    void theStockServiceDown_503_isRejectedWithTheServiceReason() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setReserveBehavior("SKU-A", Behavior.ERROR_500);

        checkout(userToken).andExpect(status().isServiceUnavailable());

        Line rejected = logs.single("order_rejected");
        assertThat(rejected.level()).isEqualTo("WARN");
        assertThat(rejected.field("reason")).isEqualTo("The stock service did not answer. Please try again.");
    }

    @Test
    void theReasonInTheLog_isCleanedAndCut_evenWhenInventorySendsLineBreaksAndALongText() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        String detail = "Not enough\\r\\nINFO forged line " + "y".repeat(600);
        shop.inventoryServer.on("POST", "/api/inventory/sku/SKU-A/reserve",
                request -> Reply.json(409, "{\"detail\":\"" + detail + "\"}"));

        checkout(userToken).andExpect(status().isConflict());

        Line rejected = logs.single("order_rejected");
        assertThat(rejected.hasControlCharacters()).isFalse();
        assertThat((String) rejected.field("reason")).startsWith("Not enough__INFO forged line").hasSizeLessThanOrEqualTo(200);
        assertThat(logs.ownLines().stream().filter(Line::hasControlCharacters)).isEmpty();
    }

    // ================================================================== release failures

    @Test
    void aReleaseThatFails_logsReleaseFailedAtError_withOrderIdAndSku_oncePerFailedLine() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        shop.setReleaseBehavior("SKU-A", Behavior.ERROR_500);

        MvcResult result = checkout(userToken).andExpect(status().isConflict()).andReturn();
        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("orderId").asLong();

        Line failed = logs.single("release_failed");
        assertThat(failed.level()).isEqualTo("ERROR");
        assertThat(failed.logger()).isEqualTo("audit");
        assertThat(((Number) failed.field("orderId")).longValue()).isEqualTo(orderId);
        assertThat(failed.field("sku")).isEqualTo("SKU-A");
        assertThat(failed.fields().keySet()).containsExactlyInAnyOrder("event", "orderId", "sku");
        assertThat(failed.mdc("userId")).isEqualTo(String.valueOf(userId));
        // The order is still rejected: a failing release never changes the answer.
        assertThat(logs.single("order_rejected")).isNotNull();
        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
    }

    @Test
    void whenEveryReleaseFails_thereIsOneEventPerLine() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        shop.setReleaseBehavior("SKU-A", Behavior.DROP);
        shop.setReleaseBehavior("SKU-B", Behavior.ERROR_500);

        checkout(userToken).andExpect(status().isConflict());

        assertThat(logs.events("release_failed")).extracting(line -> line.field("sku"))
                .containsExactlyInAnyOrder("SKU-A", "SKU-B");
    }

    @Test
    void aReleaseThatWorks_logsNoReleaseFailed() throws Exception {
        shop.addToCart(userId, "SKU-B", 9, "20.50");

        checkout(userToken).andExpect(status().isConflict());

        assertThat(logs.events("release_failed")).isEmpty();
    }

    // ================================================================== the service token

    @Test
    void everyCallToInventory_logsServiceTokenIssuedAtDebug_withTheLifetime_andNeverTheToken() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");

        checkout(userToken).andExpect(status().isCreated());

        List<Line> issued = logs.events("service_token_issued");
        assertThat(issued).as("one per reserve call").hasSize(2);
        for (Line line : issued) {
            assertThat(line.level()).isEqualTo("DEBUG");
            assertThat(line.logger()).isEqualTo("audit");
            assertThat(line.field("lifetimeSeconds")).isEqualTo(120);
            assertThat(line.fields().keySet()).containsExactlyInAnyOrder("event", "lifetimeSeconds");
        }
        for (var request : shop.inventoryServer.requests()) {
            String token = request.header("Authorization").substring("Bearer ".length());
            assertThat(logs.allText()).doesNotContain(token);
        }
    }

    @Test
    void issuingATokenDirectly_logsOneEvent_andNeverTheToken() {
        String token = tokenIssuer.issue();

        assertThat(logs.single("service_token_issued").field("lifetimeSeconds")).isEqualTo(120);
        assertThat(logs.allText()).doesNotContain(token);
    }

    @Test
    void aRejectedCheckout_issuesATokenForTheReserve_andOneForEachRelease() throws Exception {
        shop.addToCart(userId, "SKU-B", 9, "20.50");

        checkout(userToken).andExpect(status().isConflict());

        assertThat(shop.inventoryServer.requests()).hasSize(2);
        assertThat(logs.events("service_token_issued")).hasSize(2);
    }

    @Test
    void serviceTokenIssuedIsNotShown_atTheDefaultAuditLevelInfo() throws Exception {
        // The event is DEBUG: with the audit logger at INFO (as configured) it is not written.
        ch.qos.logback.classic.Logger audit = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("audit");
        ch.qos.logback.classic.Level old = audit.getLevel();
        audit.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            tokenIssuer.issue();
            assertThat(logs.events("service_token_issued")).isEmpty();
        } finally {
            audit.setLevel(old);
        }
    }

    // ================================================================== logging never changes the outcome

    @Test
    void theAnswerAndTheOrder_areTheSameWhateverIsLogged() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();
        JsonNode order = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("cartCleared").asBoolean()).isTrue();
        assertThat(statusOf(order.get("id").asLong())).isEqualTo("CONFIRMED");
        assertThat(shop.stockOf("SKU-A")).isEqualTo(8);
    }
}
