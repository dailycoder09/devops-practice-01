package com.microservices.product.logging;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.microservices.product.security.JwtTestSupport;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Test 8: the trace goes out on the call to inventory-service. The "next service" is a real local HTTP
 * server; the caller is the real application. The trace id in the header the fake receives must be the
 * trace id in the caller's own log lines for the same request.
 */
class TracePropagationTest extends AbstractLoggingIT {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private String createProduct() throws Exception {
        String sku = "PROP-" + COUNTER.incrementAndGet() + "-" + (System.nanoTime() % 100000);
        MvcResult result = mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN"))
                .content("{\"name\":\"Propagation Product\",\"price\":10.50,\"category\":\"Electronics\",\"skuCode\":\"" + sku + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        capture.clear();
        INVENTORY.clear();
        return sku;
    }

    private static String traceIdOf(String traceparent) {
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        return traceparent.split("-")[1];
    }

    private static String padded(String traceId) {
        return "0".repeat(32 - traceId.length()) + traceId;
    }

    @Test
    void theCallToInventoryService_carriesTheTraceOfTheRequest() throws Exception {
        String sku = createProduct();

        MvcResult result = mockMvc.perform(get("/api/products/sku/{sku}", sku)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(INVENTORY.received()).hasSize(1);
        FakeDownstreamServer.Received call = INVENTORY.last();
        assertThat(call.path()).isEqualTo("/api/inventory/sku/" + sku);
        String traceIdOnTheWire = traceIdOf(call.headers().get("traceparent"));

        String responseTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(padded(responseTraceId)).isEqualTo(traceIdOnTheWire);
        LogCapture.Line access = capture.accessLines("/api/products/sku/" + sku).get(0);
        assertThat(padded(access.text("traceId"))).isEqualTo(traceIdOnTheWire);

        // every line of this request, including the one about the downstream call, carries the same trace id
        var downstream = capture.withEvent("downstream_call");
        assertThat(downstream).hasSize(1);
        assertThat(padded(downstream.get(0).text("traceId"))).isEqualTo(traceIdOnTheWire);
        for (LogCapture.Line line : capture.lines()) {
            if (line.has("traceId")) {
                assertThat(padded(line.text("traceId"))).as(line.raw()).isEqualTo(traceIdOnTheWire);
            }
        }
    }

    @Test
    void theDownstreamCall_isLoggedAtDebug_withoutHeadersOrQuery() throws Exception {
        String sku = createProduct();

        mockMvc.perform(get("/api/products/sku/{sku}", sku).header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("USER")));

        LogCapture.Line line = capture.withEvent("downstream_call").get(0);
        assertThat(line.level()).isEqualTo("DEBUG");
        assertThat(line.text("downstreamService")).isEqualTo("inventory-service");
        assertThat(line.text("httpMethod")).isEqualTo("GET");
        assertThat(line.text("httpPath")).isEqualTo("/api/inventory/sku/" + sku);
        assertThat(line.number("httpStatus")).isEqualTo(200);
        assertThat(line.number("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(line.raw()).doesNotContain("Bearer").doesNotContain("traceparent").doesNotContain("authorization");
    }

    @Test
    void anIncomingTraceparent_isContinuedOnTheCallToInventoryService() throws Exception {
        String sku = createProduct();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        mockMvc.perform(get("/api/products/sku/{sku}", sku)
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

        assertThat(traceIdOf(INVENTORY.last().headers().get("traceparent"))).isEqualTo(traceId);
        assertThat(capture.accessLines("/api/products/sku/" + sku).get(0).text("traceId")).isEqualTo(traceId);
    }

    @Test
    void twoRequests_sendTwoDifferentTraces() throws Exception {
        String sku = createProduct();

        mockMvc.perform(get("/api/products/sku/{sku}", sku));
        mockMvc.perform(get("/api/products/sku/{sku}", sku));

        assertThat(INVENTORY.received()).hasSize(2);
        assertThat(traceIdOf(INVENTORY.received().get(0).headers().get("traceparent")))
                .isNotEqualTo(traceIdOf(INVENTORY.received().get(1).headers().get("traceparent")));
    }

    @Test
    void theCallersToken_isNotPassedToInventoryService() throws Exception {
        String sku = createProduct();

        mockMvc.perform(get("/api/products/sku/{sku}", sku).header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("USER")));

        assertThat(INVENTORY.last().headers()).doesNotContainKey("authorization");
    }

    @Test
    void whenInventoryServiceAnswers500_theProductStillAnswers_andOneWarnIsWritten() throws Exception {
        String sku = createProduct();
        INVENTORY.respondWith(500, "{}");

        MvcResult result = mockMvc.perform(get("/api/products/sku/{sku}", sku)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var downstream = capture.withEvent("downstream_call");
        assertThat(downstream).hasSize(1);
        assertThat(downstream.get(0).level()).isEqualTo("WARN");
        assertThat(downstream.get(0).number("httpStatus")).isEqualTo(500);
        // the failure is reported once (by the interceptor), not again by the client that swallows it
        assertThat(capture.lines().stream().filter(line -> "WARN".equals(line.level()) || "ERROR".equals(line.level())))
                .hasSize(1);
    }
}
