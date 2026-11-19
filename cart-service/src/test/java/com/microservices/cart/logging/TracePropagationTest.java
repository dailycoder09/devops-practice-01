package com.microservices.cart.logging;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.support.AbstractCartLoggingTest;
import com.microservices.cart.support.FakeProductService;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 8, the propagation test: a fake product-service receives the trace header of every call the cart makes,
 * and the trace id in that header is the trace id in the cart's own log lines for the request (and in the
 * X-Trace-Id header the caller gets back). The call goes out through the RestClient built from Spring's
 * RestClient.Builder, which Spring Boot connects to the tracing by itself.
 */
class TracePropagationTest extends AbstractCartLoggingTest {

    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]");

    private static String traceIdOf(FakeProductService.Received request) {
        assertThat(request.traceparent()).as("traceparent header of " + request.rawPath()).isNotNull();
        Matcher matcher = TRACEPARENT.matcher(request.traceparent());
        assertThat(matcher.matches()).as("W3C traceparent: " + request.traceparent()).isTrue();
        return matcher.group(1);
    }

    @Test
    void everyCallToProductService_carriesTheTraceOfTheRequest() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        PRODUCTS.addProduct("PHONE-2", "Other Phone", "19.99");
        String token = TestTokens.user(TestTokens.newUserId());
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        setQuantity(token, "PHONE-2", 2).andExpect(status().isOk());
        // the set-up calls are over, now the call that is checked
        int before = PRODUCTS.requests().size();
        logs.clear();
        logs.level(DownstreamLoggingInterceptor.class.getName(), Level.DEBUG);

        MvcResult result = getCart(token).andExpect(status().isOk()).andReturn();

        List<FakeProductService.Received> calls = PRODUCTS.requests().subList(before, PRODUCTS.requests().size());
        assertThat(calls).hasSize(2);

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}");
        Set<String> spanIds = new HashSet<>();
        for (FakeProductService.Received call : calls) {
            assertThat(traceIdOf(call)).isEqualTo(traceId);
            spanIds.add(call.traceparent().split("-")[2]);
            assertThat(call.authorization()).isNull();
        }
        assertThat(spanIds).as("each call is its own span").hasSize(2);

        // ... and it is the trace id of the cart's own log lines for that request
        assertThat(logs.singleAccessLine("/api/cart").get("traceId").asText()).isEqualTo(traceId);
        List<JsonNode> downstream = logs.events("downstream_call");
        assertThat(downstream).hasSize(2);
        assertThat(downstream).allSatisfy(line -> assertThat(line.get("traceId").asText()).isEqualTo(traceId));
        assertThat(logs.all()).allSatisfy(line -> {
            if (line.has("traceId")) {
                assertThat(line.get("traceId").asText()).isEqualTo(traceId);
            }
        });
    }

    @Test
    void theLookupOfASetQuantity_carriesTheTraceToo_andTheAuditEventHasTheSameTraceId() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        String token = TestTokens.user(TestTokens.newUserId());
        logs.clear();

        MvcResult result = setQuantity(token, "PHONE-1", 2).andExpect(status().isOk()).andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(PRODUCTS.requests()).isNotEmpty();
        assertThat(PRODUCTS.requests()).allSatisfy(call -> assertThat(traceIdOf(call)).isEqualTo(traceId));
        assertThat(logs.single("cart_item_set").get("traceId").asText()).isEqualTo(traceId);
    }

    @Test
    void aTraceThatArrivesFromAnotherService_isContinuedToProductService() throws Exception {
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        String token = TestTokens.user(TestTokens.newUserId());
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        int before = PRODUCTS.requests().size();
        String incoming = "4bf92f3577b34da6a3ce929d0e0e4736";

        MvcResult result = mockMvc.perform(get("/api/cart")
                        .header("Authorization", TestTokens.bearer(token))
                        .header("traceparent", "00-" + incoming + "-00f067aa0ba902b7-01"))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(incoming);
        List<FakeProductService.Received> calls = PRODUCTS.requests().subList(before, PRODUCTS.requests().size());
        assertThat(calls).hasSize(1);
        assertThat(traceIdOf(calls.get(0))).isEqualTo(incoming);
        // the call is a new span of the same trace, not the span of the caller
        assertThat(calls.get(0).traceparent()).doesNotContain("00f067aa0ba902b7");
    }

    @Test
    void whenProductServiceFails_theFailedCallIsLoggedWithTheSameTraceId() throws Exception {
        String token = TestTokens.user(TestTokens.newUserId());
        PRODUCTS.addProduct("PHONE-1", "Test Phone", "9.99");
        setQuantity(token, "PHONE-1", 1).andExpect(status().isOk());
        PRODUCTS.failWithStatus(500);
        logs.clear();

        MvcResult result = getCart(token).andExpect(status().isServiceUnavailable()).andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        JsonNode call = logs.single("downstream_call");
        assertThat(call.get("level").asText()).isEqualTo("WARN");
        assertThat(call.get("httpStatus").asInt()).isEqualTo(500);
        assertThat(call.get("traceId").asText()).isEqualTo(traceId);
        assertThat(logs.singleAccessLine("/api/cart").get("httpStatus").asInt()).isEqualTo(503);
    }
}
