package com.microservices.inventory.logging;

import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Test 8: the trace goes out on the call to product-service (the SKU check when a stock record is created).
 * The "next service" is a real local HTTP server; the caller is the real application. The trace id in the
 * header the fake receives must be the trace id in the caller's own log lines for the same request.
 */
class TracePropagationTest extends AbstractLoggingIT {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static String newSku() {
        return "PROP-" + COUNTER.incrementAndGet() + "-" + (System.nanoTime() % 100000);
    }

    private MvcResult createInventory(String sku, String... extraHeaders) throws Exception {
        var request = post("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN"))
                .content("{\"skuCode\":\"" + sku + "\",\"quantityOnHand\":3}");
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            request.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String traceIdOf(String traceparent) {
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        return traceparent.split("-")[1];
    }

    private static String padded(String traceId) {
        return "0".repeat(32 - traceId.length()) + traceId;
    }

    @Test
    void theCallToProductService_carriesTheTraceOfTheRequest() throws Exception {
        String sku = newSku();

        MvcResult result = createInventory(sku);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(PRODUCTS.received()).hasSize(1);
        FakeDownstreamServer.Received call = PRODUCTS.last();
        assertThat(call.path()).isEqualTo("/api/products/sku/" + sku);
        String traceIdOnTheWire = traceIdOf(call.headers().get("traceparent"));

        String responseTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(padded(responseTraceId)).isEqualTo(traceIdOnTheWire);
        LogCapture.Line access = capture.accessLines("/api/inventory").get(0);
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
        String sku = newSku();

        createInventory(sku);

        LogCapture.Line line = capture.withEvent("downstream_call").get(0);
        assertThat(line.level()).isEqualTo("DEBUG");
        assertThat(line.text("downstreamService")).isEqualTo("product-service");
        assertThat(line.text("httpMethod")).isEqualTo("GET");
        assertThat(line.text("httpPath")).isEqualTo("/api/products/sku/" + sku);
        assertThat(line.number("httpStatus")).isEqualTo(200);
        assertThat(line.number("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(line.raw()).doesNotContain("Bearer").doesNotContain("traceparent").doesNotContain("authorization");
    }

    @Test
    void anIncomingTraceparent_isContinuedOnTheCallToProductService() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        createInventory(newSku(), "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");

        assertThat(traceIdOf(PRODUCTS.last().headers().get("traceparent"))).isEqualTo(traceId);
        assertThat(capture.accessLines("/api/inventory").get(0).text("traceId")).isEqualTo(traceId);
    }

    @Test
    void twoRequests_sendTwoDifferentTraces() throws Exception {
        createInventory(newSku());
        createInventory(newSku());

        assertThat(PRODUCTS.received()).hasSize(2);
        assertThat(traceIdOf(PRODUCTS.received().get(0).headers().get("traceparent")))
                .isNotEqualTo(traceIdOf(PRODUCTS.received().get(1).headers().get("traceparent")));
    }

    @Test
    void theCallersToken_isNotPassedToProductService() throws Exception {
        createInventory(newSku());

        assertThat(PRODUCTS.last().headers()).doesNotContainKey("authorization");
    }

    @Test
    void whenProductServiceAnswers500_theCallerAnswers503_andTheDownstreamWarnIsWrittenOnce() throws Exception {
        PRODUCTS.respondWith(500, "{}");

        MvcResult result = createInventory(newSku());

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        var downstream = capture.withEvent("downstream_call");
        assertThat(downstream).hasSize(1);
        assertThat(downstream.get(0).level()).isEqualTo("WARN");
        assertThat(downstream.get(0).number("httpStatus")).isEqualTo(500);
        assertThat(capture.accessLines("/api/inventory").get(0).level()).isEqualTo("ERROR");
    }
}
