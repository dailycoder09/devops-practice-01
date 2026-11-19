package com.microservices.order.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.support.JwtTestSupport;
import com.microservices.order.support.LoggingTestBase;
import com.microservices.order.support.RealLogFile;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 4, on the real application: the JSON file that the running service writes (not a copy of the configuration).
 * Every line parses, the access line and the audit events have their fields, and the trace id of a request can be
 * found in the file, as the lead will do with grep and jq.
 */
class RealLogFileTest extends LoggingTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(15);

    private static JsonNode parse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("A line of the log file is not valid JSON: " + line, e);
        }
    }

    private static List<JsonNode> withTrace(List<String> lines, String traceId) {
        List<JsonNode> found = new ArrayList<>();
        for (String line : lines) {
            JsonNode json = parse(line);
            if (json.has("traceId") && traceId.equals(json.get("traceId").asText())) {
                found.add(json);
            }
        }
        return found;
    }

    private static boolean hasEvent(List<String> lines, String traceId, String event) {
        return withTrace(lines, traceId).stream().anyMatch(json -> json.has("event") && event.equals(json.get("event").asText()));
    }

    @Test
    void aRequest_endsUpInTheFile_asOneJsonLine_withEveryField() throws Exception {
        long offset = RealLogFile.size();

        MvcResult result = mockMvc.perform(getWith("/api/orders", userToken)
                .header("X-Forwarded-For", "203.0.113.77")).andExpect(status().isOk()).andReturn();
        String traceId = traceIdHeader(result);
        List<String> lines = RealLogFile.await(offset, found -> hasEvent(found, traceId, "http_request"), WAIT);

        JsonNode access = withTrace(lines, traceId).stream()
                .filter(json -> "http_request".equals(json.path("event").asText())).findFirst().orElseThrow();
        assertThat(OffsetDateTime.parse(access.get("timestamp").asText()).getOffset().getTotalSeconds()).isZero();
        assertThat(access.get("level").asText()).isEqualTo("INFO");
        assertThat(access.get("service").asText()).isEqualTo("order-service");
        assertThat(access.get("profile").asText()).isNotBlank();
        assertThat(access.get("logger").asText()).endsWith("RequestLoggingFilter");
        assertThat(access.get("thread").asText()).isNotBlank();
        assertThat(access.get("message").asText()).matches("HTTP GET /api/orders -> 200 in \\d+ ms");
        assertThat(access.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(access.get("httpPath").asText()).isEqualTo("/api/orders");
        assertThat(access.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(access.get("durationMs").isIntegralNumber()).isTrue();
        assertThat(access.get("userId").asText()).isEqualTo(String.valueOf(userId));
        assertThat(access.get("userEmail").asText()).isEqualTo("user" + userId + "@example.com");
        assertThat(access.get("userRole").asText()).isEqualTo("USER");
        assertThat(access.get("clientIp").asText()).isEqualTo("203.0.113.77");
        assertThat(access.get("traceId").asText()).isEqualTo(traceId);
        assertThat(access.get("spanId").asText()).isNotBlank();
        assertThat(access.has("stack_trace")).isFalse();
    }

    @Test
    void aWholeCheckout_canBeFollowedByItsTraceId_inTheFile() throws Exception {
        long offset = RealLogFile.size();
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();
        String traceId = traceIdHeader(result);
        List<String> lines = RealLogFile.await(offset, found -> hasEvent(found, traceId, "http_request"), WAIT);

        List<JsonNode> ofTheRequest = withTrace(lines, traceId);
        assertThat(ofTheRequest.stream().map(json -> json.path("event").asText()))
                .contains("order_checkout_started", "order_confirmed", "downstream_call", "http_request");
        assertThat(ofTheRequest).allSatisfy(json -> {
            assertThat(json.get("userId").asText()).isEqualTo(String.valueOf(userId));
            assertThat(json.get("clientIp").asText()).isNotBlank();
        });
        JsonNode confirmed = ofTheRequest.stream().filter(json -> "order_confirmed".equals(json.path("event").asText()))
                .findFirst().orElseThrow();
        assertThat(confirmed.get("logger").asText()).isEqualTo("audit");
        assertThat(confirmed.get("total").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(confirmed.get("cartCleared").asBoolean()).isTrue();
    }

    @Test
    void everyLineWrittenDuringTheTest_isValidJson_evenForHostileValues() throws Exception {
        long offset = RealLogFile.size();
        String badEmail = "evil\"quote@example.com\r\nINFO forged";
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, String.valueOf(userId), badEmail, "USER");
        mockMvc.perform(getWith("/api/orders", token));
        mockMvc.perform(getWith("/api/orders", "garbage"));
        mockMvc.perform(getWith("/api/orders/abc\"quoted", userToken));
        Logger direct = LoggerFactory.getLogger("com.microservices.order.test.RealLogFileTest");
        direct.warn("Quotes \" and a line\nbreak and a very long value {}", "w".repeat(50_000));
        direct.error("with a stack trace", new IllegalStateException("first line\nsecond line"));
        direct.info("marker line of this test");

        List<String> lines = RealLogFile.await(offset,
                found -> found.stream().anyMatch(line -> line.contains("marker line of this test")), WAIT);

        assertThat(lines).isNotEmpty();
        lines.forEach(RealLogFileTest::parse);
        JsonNode error = lines.stream().map(RealLogFileTest::parse)
                .filter(json -> "with a stack trace".equals(json.path("message").asText())).findFirst().orElseThrow();
        assertThat(error.get("stack_trace").asText()).contains("IllegalStateException: first line");
        JsonNode warn = lines.stream().map(RealLogFileTest::parse)
                .filter(json -> json.path("message").asText().startsWith("Quotes \" and a line\nbreak")).findFirst().orElseThrow();
        assertThat(warn.get("message").asText()).hasSize("Quotes \" and a line\nbreak and a very long value ".length() + 50_000);
        // A line break in a value never becomes a second line: the file has exactly one line per event.
        assertThat(lines.stream().noneMatch(line -> line.startsWith("INFO forged"))).isTrue();
    }
}
