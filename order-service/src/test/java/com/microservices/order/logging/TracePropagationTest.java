package com.microservices.order.logging;

import com.microservices.order.support.FakeHttpServer.Request;
import com.microservices.order.support.FakeShop.Behavior;
import com.microservices.order.support.LogCapture.Line;
import com.microservices.order.support.LoggingTestBase;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 8 of the logging contract: the trace follows the request to the other services. Fake cart, product and
 * inventory servers record what they receive. The traceId they get must be the traceId in the response header and
 * in the log lines of the caller, and the tokens that were already passed on must be unchanged.
 */
class TracePropagationTest extends LoggingTestBase {

    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING_SPAN_ID = "00f067aa0ba902b7";

    private static String traceIdOf(Request request) {
        String traceparent = request.header("traceparent");
        assertThat(traceparent).as("traceparent header of " + request.method() + " " + request.path()).isNotNull();
        return traceIdOfTraceparent(traceparent);
    }

    private static List<Request> allDownstreamRequests() {
        return Stream.of(shop.cartServer, shop.productServer, shop.inventoryServer)
                .flatMap(server -> server.requests().stream()).toList();
    }

    // ================================================================== the three services all get the trace

    @Test
    void everyCallOfACheckout_toCartProductAndInventory_carriesTheTraceOfTheRequest() throws Exception {
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        shop.addToCart(userId, "SKU-B", 1, "20.50");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();
        String traceId = normalizeTraceId(traceIdHeader(result));

        // cart: read and clear; product: two lookups; inventory: two reserves
        assertThat(shop.cartServer.requests()).extracting(Request::method).containsExactly("GET", "DELETE");
        assertThat(shop.productServer.requests()).hasSize(2);
        assertThat(shop.inventoryServer.requests()).hasSize(2);
        for (Request request : allDownstreamRequests()) {
            assertThat(traceIdOf(request)).as(request.method() + " " + request.path()).isEqualTo(traceId);
        }
    }

    @Test
    void theTraceIdTheOtherServicesReceive_isTheTraceIdInTheCallersLogLines_ofThatRequest() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();
        String header = traceIdHeader(result);

        List<Line> ofThisRequest = logs.lines(line -> header.equals(line.mdc("traceId")));
        // The audit events, the access line and every downstream line of the request carry that one trace id.
        assertThat(ofThisRequest.stream().filter(line -> line.isEvent("order_checkout_started"))).hasSize(1);
        assertThat(ofThisRequest.stream().filter(line -> line.isEvent("order_confirmed"))).hasSize(1);
        assertThat(ofThisRequest.stream().filter(line -> line.isEvent("http_request"))).hasSize(1);
        List<Line> downstream = ofThisRequest.stream().filter(line -> line.isEvent("downstream_call")).toList();
        assertThat(downstream).extracting(line -> line.field("downstreamService"))
                .containsExactlyInAnyOrder("cart-service", "product-service", "inventory-service", "cart-service");
        // ... and the same id is what each fake server received.
        for (Request request : allDownstreamRequests()) {
            assertThat(traceIdOf(request)).isEqualTo(normalizeTraceId(header));
        }
        // No line of this request is missing the trace: every line of the checkout has a trace id.
        assertThat(logs.lines(line -> line.logger().startsWith("com.microservices") || line.logger().equals("audit")))
                .allSatisfy(line -> assertThat(line.mdc("traceId")).isEqualTo(header));
    }

    @Test
    void eachCallIsItsOwnSpan_ofTheSameTrace() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken).andExpect(status().isCreated());

        List<String> spanIds = allDownstreamRequests().stream()
                .map(request -> request.header("traceparent").split("-")[2]).toList();
        assertThat(spanIds).hasSize(4).doesNotHaveDuplicates();
    }

    @Test
    void anIncomingTraceparent_isContinuedToTheOtherServices() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                        .header("traceparent", "00-" + INCOMING_TRACE_ID + "-" + INCOMING_SPAN_ID + "-01"))
                .andExpect(status().isCreated()).andReturn();

        assertThat(normalizeTraceId(traceIdHeader(result))).isEqualTo(INCOMING_TRACE_ID);
        for (Request request : allDownstreamRequests()) {
            assertThat(traceIdOf(request)).isEqualTo(INCOMING_TRACE_ID);
            // The caller's span is the parent, so the span id sent on is a new one.
            assertThat(request.header("traceparent").split("-")[2]).isNotEqualTo(INCOMING_SPAN_ID);
        }
    }

    @Test
    void twoRequests_sendTwoDifferentTraces() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        MvcResult first = checkout(userToken).andExpect(status().isCreated()).andReturn();
        List<String> firstTraces = allDownstreamRequests().stream().map(TracePropagationTest::traceIdOf).distinct().toList();
        shop.cartServer.clearRequests();
        shop.productServer.clearRequests();
        shop.inventoryServer.clearRequests();
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        MvcResult second = checkout(userToken).andExpect(status().isCreated()).andReturn();
        List<String> secondTraces = allDownstreamRequests().stream().map(TracePropagationTest::traceIdOf).distinct().toList();

        assertThat(firstTraces).containsExactly(normalizeTraceId(traceIdHeader(first)));
        assertThat(secondTraces).containsExactly(normalizeTraceId(traceIdHeader(second)));
        assertThat(firstTraces).isNotEqualTo(secondTraces);
    }

    // ================================================================== also on the failure paths

    @Test
    void theReleaseCalls_afterARefusal_carryTheTraceToo() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 9, "20.50");

        MvcResult result = checkout(userToken).andExpect(status().isConflict()).andReturn();

        assertThat(releaseCalls()).hasSize(2);
        for (Request request : shop.inventoryServer.requests()) {
            assertThat(traceIdOf(request)).isEqualTo(normalizeTraceId(traceIdHeader(result)));
        }
    }

    @Test
    void aCallThatFails_stillCarriedTheTrace_andIsLoggedWithTheSameTrace() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.setReserveBehavior("SKU-A", Behavior.ERROR_500);

        MvcResult result = checkout(userToken).andExpect(status().isServiceUnavailable()).andReturn();

        assertThat(reserveCalls()).hasSize(1);
        assertThat(traceIdOf(reserveCalls().get(0))).isEqualTo(normalizeTraceId(traceIdHeader(result)));
        Line failed = logs.lines(line -> line.isEvent("downstream_call") && Integer.valueOf(500).equals(line.field("httpStatus")))
                .get(0);
        assertThat(failed.level()).isEqualTo("WARN");
        assertThat(failed.field("downstreamService")).isEqualTo("inventory-service");
        assertThat(failed.mdc("traceId")).isEqualTo(traceIdHeader(result));
    }

    @Test
    void aProductThatIsGone_404_alsoSawTheTrace() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.removeProduct("SKU-A");

        MvcResult result = checkout(userToken).andExpect(status().isUnprocessableEntity()).andReturn();

        assertThat(shop.productServer.requests()).hasSize(1);
        assertThat(traceIdOf(shop.productServer.requests().get(0))).isEqualTo(normalizeTraceId(traceIdHeader(result)));
    }

    // ================================================================== the tokens are unchanged

    @Test
    void theServiceToken_isUnchanged_byTheTracing() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");
        shop.addToCart(userId, "SKU-B", 9, "20.50");

        checkout(userToken).andExpect(status().isConflict());

        assertThat(shop.inventoryServer.requests()).hasSize(4);
        for (Request request : shop.inventoryServer.requests()) {
            JWTClaimsSet claims = verifiedClaims(request.header(HttpHeaders.AUTHORIZATION));
            assertThat(claims.getSubject()).isEqualTo("order-service");
            assertThat(claims.getClaim("role")).isEqualTo("SERVICE");
            assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("sub", "role", "iat", "exp");
            assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                    .isEqualTo(Duration.ofMinutes(2));
            // Only one Authorization header, a plain Bearer token.
            assertThat(request.header(HttpHeaders.AUTHORIZATION)).startsWith("Bearer ey").doesNotContain(",");
        }
    }

    @Test
    void theCallersOwnToken_isStillPassedToTheCart_exactlyAsItCame() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken).andExpect(status().isCreated());

        assertThat(shop.cartServer.requests()).hasSize(2)
                .allSatisfy(request -> assertThat(request.header(HttpHeaders.AUTHORIZATION)).isEqualTo(bearer(userToken)));
    }

    @Test
    void theProductLookup_stillHasNoToken() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken).andExpect(status().isCreated());

        assertThat(shop.productServer.requests()).hasSize(1)
                .allSatisfy(request -> assertThat(request.header(HttpHeaders.AUTHORIZATION)).isNull());
    }

    @Test
    void theCallersToken_isNotCopiedIntoAnyOtherHeader_ofTheCallsToTheOtherServices() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        checkout(userToken).andExpect(status().isCreated());

        for (Request request : allDownstreamRequests()) {
            request.headers().forEach((name, value) -> {
                if (!name.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)) {
                    assertThat(value).as("header " + name).doesNotContain(userToken);
                }
            });
        }
    }
}
