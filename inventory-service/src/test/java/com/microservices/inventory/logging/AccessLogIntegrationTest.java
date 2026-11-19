package com.microservices.inventory.logging;

import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Tests 1, 2, 3 and 6 of the logging contract against the real application: the access line, the levels,
 * MDC clean-up, and log injection. Also the log lines of the security handlers and the exception handler.
 */
class AccessLogIntegrationTest extends AbstractLoggingIT {

    private static final String TRACE_ID_PATTERN = "[0-9a-f]{16}|[0-9a-f]{32}";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RequestLoggingFilter requestLoggingFilter;

    // ------------------------------------------------------------------ test 1: the access line

    @Test
    void anonymousRequest_writesOneAccessLine_withTraceAndClientFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/inventory")).andReturn();

        var lines = capture.accessLines("/api/inventory");
        assertThat(lines).hasSize(1);
        LogCapture.Line line = lines.get(0);
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.text("httpMethod")).isEqualTo("GET");
        assertThat(line.number("httpStatus")).isEqualTo(200);
        assertThat(line.number("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(line.message()).matches("HTTP GET /api/inventory -> 200 in \\d+ ms");
        assertThat(line.text("userId")).isEqualTo("anonymous");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(line.has("userRole")).isFalse();
        assertThat(line.text("clientIp")).isEqualTo("127.0.0.1");
        assertThat(line.text("traceId")).matches(TRACE_ID_PATTERN);
        assertThat(line.text("spanId")).isNotBlank();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(line.text("traceId"));
    }

    @Test
    void authenticatedRequest_carriesTheUserFields() throws Exception {
        mockMvc.perform(get("/api/inventory")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "USER")));

        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.number("httpStatus")).isEqualTo(200);
        assertThat(line.text("userId")).isEqualTo("77");
        assertThat(line.text("userEmail")).isEqualTo("anna@example.com");
        assertThat(line.text("userRole")).isEqualTo("USER");
        assertThat(line.text("clientIp")).isEqualTo("127.0.0.1");
        assertThat(line.text("traceId")).matches(TRACE_ID_PATTERN);
    }

    @Test
    void aServiceToken_showsTheServiceNameAsTheUser() throws Exception {
        mockMvc.perform(get("/api/inventory").header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));

        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.text("userId")).isEqualTo("order-service");
        assertThat(line.text("userRole")).isEqualTo("SERVICE");
        assertThat(line.has("userEmail")).isTrue(); // the test token carries an email claim
    }

    @Test
    void forwardedFor_winsOverTheSocketAddress() throws Exception {
        mockMvc.perform(get("/api/inventory").header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"));

        assertThat(capture.accessLines("/api/inventory").get(0).text("clientIp")).isEqualTo("203.0.113.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip", "1.2.3.4\r\nforged: yes", "<script>alert(1)</script>"})
    void aBadForwardedFor_isIgnored(String header) throws Exception {
        mockMvc.perform(get("/api/inventory").header("X-Forwarded-For", header));

        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.text("clientIp")).isEqualTo("127.0.0.1");
        assertThat(line.raw()).doesNotContain("forged").doesNotContain("script");
    }

    @Test
    void aVeryLongForwardedFor_isIgnored() throws Exception {
        mockMvc.perform(get("/api/inventory").header("X-Forwarded-For", "9".repeat(3000)));

        assertThat(capture.accessLines("/api/inventory").get(0).text("clientIp")).isEqualTo("127.0.0.1");
    }

    @Test
    void theQueryString_isNotLogged() throws Exception {
        mockMvc.perform(get("/api/inventory").param("page", "0").param("secretParam", "hunter2-value"));

        assertThat(capture.accessLines("/api/inventory")).hasSize(1);
        assertThat(capture.allText()).doesNotContain("hunter2-value").doesNotContain("secretParam");
    }

    @Test
    void theTraceHeader_equalsTheLoggedTraceId_andEveryRequestGetsItsOwn() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/inventory")).andReturn();
        MvcResult second = mockMvc.perform(get("/api/inventory/batch")).andReturn();

        String firstId = first.getResponse().getHeader("X-Trace-Id");
        String secondId = second.getResponse().getHeader("X-Trace-Id");
        assertThat(firstId).matches(TRACE_ID_PATTERN);
        assertThat(secondId).matches(TRACE_ID_PATTERN).isNotEqualTo(firstId);
        assertThat(capture.accessLines("/api/inventory").get(0).text("traceId")).isEqualTo(firstId);
        assertThat(capture.accessLines("/api/inventory/batch").get(0).text("traceId")).isEqualTo(secondId);
    }

    @Test
    void anIncomingTraceparent_isContinued() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        MvcResult result = mockMvc.perform(get("/api/inventory")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")).andReturn();

        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(traceId);
        assertThat(capture.accessLines("/api/inventory").get(0).text("traceId")).isEqualTo(traceId);
    }

    @Test
    void everyRequestWritesExactlyOneAccessLine_notOnePerFilterLayer() throws Exception {
        mockMvc.perform(get("/api/inventory"));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertThat(capture.accessLines("/api/inventory")).hasSize(2);
    }

    @Test
    void theUserFilterIsOnlyInTheSecurityChain_notAlsoAServletFilter() {
        assertThat(context.getBeansOfType(UserMdcFilter.class)).isEmpty();
        assertThat(requestLoggingFilter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    // ------------------------------------------------------------------ test 2: levels

    @Test
    void healthCheck_isDebug_andStillWorksWithoutToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        LogCapture.Line line = capture.accessLines("/actuator/health").get(0);
        assertThat(line.level()).isEqualTo("DEBUG");
        assertThat(line.text("userId")).isEqualTo("anonymous");
    }

    @Test
    void noToken_on401_isWarn_andCarriesAnonymous() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.number("httpStatus")).isEqualTo(401);
        assertThat(line.text("userId")).isEqualTo("anonymous");
        assertThat(line.text("traceId")).matches(TRACE_ID_PATTERN);
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(line.text("traceId"));
    }

    @Test
    void aBadToken_on401_isWarn_andNotTrustedAsAUser() throws Exception {
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestSupport.expiredToken("ADMIN")));

        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.number("httpStatus")).isEqualTo(401);
        assertThat(line.text("userId")).isEqualTo("anonymous");
    }

    @Test
    void aUserOn403_isWarn_andTheAccessLineStillNamesTheUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "USER"))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.number("httpStatus")).isEqualTo(403);
        assertThat(line.text("userId")).isEqualTo("77");
        assertThat(line.text("userEmail")).isEqualTo("anna@example.com");
        assertThat(line.text("userRole")).isEqualTo("USER");
    }

    @Test
    void anUnexpectedFailure_is500_error_andTheStackTraceIsWrittenOnce() throws Exception {
        MvcResult result = mockMvc.perform(get("/test-probe/boom")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("kaboom");
        LogCapture.Line access = capture.accessLines("/test-probe/boom").get(0);
        assertThat(access.level()).isEqualTo("ERROR");
        assertThat(access.number("httpStatus")).isEqualTo(500);

        var withStack = capture.lines().stream().filter(line -> line.has("stack_trace")).toList();
        assertThat(withStack).hasSize(1);
        assertThat(withStack.get(0).level()).isEqualTo("ERROR");
        assertThat(withStack.get(0).text("stack_trace")).contains("IllegalStateException").contains("kaboom");
        assertThat(withStack.get(0).logger()).endsWith("GlobalExceptionHandler");
        assertThat(withStack.get(0).text("traceId")).isEqualTo(access.text("traceId"));
    }

    @Test
    void aSlowRequest_isWarn_withSlowTrue() throws Exception {
        long original = (long) ReflectionTestUtils.getField(requestLoggingFilter, "slowRequestMs");
        assertThat(original).as("default of app.logging.slow-request-ms").isEqualTo(2000L);
        ReflectionTestUtils.setField(requestLoggingFilter, "slowRequestMs", 20L);
        try {
            mockMvc.perform(get("/test-probe/slow"));
        } finally {
            ReflectionTestUtils.setField(requestLoggingFilter, "slowRequestMs", original);
        }

        LogCapture.Line line = capture.accessLines("/test-probe/slow").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.get("slow")).isEqualTo(true);
    }

    // ------------------------------------------------------------------ handled errors: levels of the exception handler

    @Test
    void aHandledClientError_isWarn_withoutAStackTrace() throws Exception {
        mockMvc.perform(get("/api/inventory/999999"));

        var handlerLines = capture.lines().stream()
                .filter(line -> line.logger() != null && line.logger().endsWith("GlobalExceptionHandler")).toList();
        assertThat(handlerLines).hasSize(1);
        assertThat(handlerLines.get(0).level()).isEqualTo("WARN");
        assertThat(capture.lines().stream().filter(line -> line.has("stack_trace"))).isEmpty();
    }

    @Test
    void aValidationError_logsTheFieldNames_butNeverTheRejectedValues() throws Exception {
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN"))
                .content("{\"skuCode\":\"   \",\"quantityOnHand\":-987654}"));

        var handlerLines = capture.lines().stream()
                .filter(line -> line.logger() != null && line.logger().endsWith("GlobalExceptionHandler")).toList();
        assertThat(handlerLines).hasSize(1);
        assertThat(handlerLines.get(0).level()).isEqualTo("WARN");
        assertThat(handlerLines.get(0).message()).contains("skuCode").contains("quantityOnHand");
        assertThat(capture.allText()).doesNotContain("987654");
    }

    // ------------------------------------------------------------------ security handlers

    @Test
    void noToken_logsAuthRejected_withReasonMissingToken() throws Exception {
        mockMvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON).content("{}"));

        var events = capture.withEvent("auth_rejected");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).level()).isEqualTo("WARN");
        assertThat(events.get(0).text("reason")).isEqualTo("missing_token");
        assertThat(events.get(0).text("traceId")).matches(TRACE_ID_PATTERN);
    }

    @Test
    void aBadToken_logsAuthRejected_withReasonInvalidToken_andNeverTheToken() throws Exception {
        String expired = JwtTestSupport.expiredToken("ADMIN");
        mockMvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        var events = capture.withEvent("auth_rejected");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).text("reason")).isEqualTo("invalid_token");
        assertThat(capture.allText()).doesNotContain(expired);
    }

    @Test
    void aUserWithoutTheRole_logsAccessDenied_withMethodAndPath() throws Exception {
        mockMvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "USER")));

        var events = capture.withEvent("access_denied");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).level()).isEqualTo("WARN");
        assertThat(events.get(0).text("httpMethod")).isEqualTo("PUT");
        assertThat(events.get(0).text("httpPath")).isEqualTo("/api/inventory/1");
        assertThat(events.get(0).text("userId")).isEqualTo("77");
    }

    // ------------------------------------------------------------------ test 3: MDC

    @Test
    void mdc_isEmptyAfterEveryKindOfRequest() throws Exception {
        mockMvc.perform(get("/api/inventory").header("X-Forwarded-For", "203.0.113.7")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "USER")));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{}"));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("USER")));
        mockMvc.perform(get("/test-probe/boom"));
        mockMvc.perform(get("/actuator/health"));

        for (String key : new String[] {"clientIp", "userId", "userEmail", "userRole", "traceId", "spanId"}) {
            assertThat(MDC.get(key)).as("MDC key " + key + " after the requests").isNull();
        }
    }

    @Test
    void theNextRequestOnTheSameThread_doesNotInheritTheLastOnesUserOrAddress() throws Exception {
        mockMvc.perform(get("/api/inventory").header("X-Forwarded-For", "203.0.113.7")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "ADMIN")));
        capture.clear();

        mockMvc.perform(get("/api/inventory/batch"));

        LogCapture.Line line = capture.accessLines("/api/inventory/batch").get(0);
        assertThat(line.text("clientIp")).isEqualTo("127.0.0.1");
        assertThat(line.text("userId")).isEqualTo("anonymous");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(line.has("userRole")).isFalse();
    }

    // ------------------------------------------------------------------ test 6: log injection

    @Test
    void aSkuWithLineBreaks_doesNotCreateASecondLineOrField() throws Exception {
        String evil = "ABC\r\n{\"event\":\"forged\",\"userId\":\"admin\"}";

        mockMvc.perform(get("/api/inventory/sku/{sku}", evil));

        assertNoForgery();
    }

    @Test
    void aPathWithRawLineBreaks_isCleanedInTheAccessLine() throws Exception {
        mockMvc.perform(get("/api/x").with(request -> {
            request.setRequestURI("/api/x\r\n{\"event\":\"forged\"}");
            return request;
        }));

        LogCapture.Line line = capture.withEvent("http_request").get(0);
        assertThat(line.text("httpPath")).isEqualTo("/api/x__{\"event\":\"forged\"}");
        assertNoForgery();
    }

    @Test
    void anEmailClaimWithLineBreaks_doesNotCreateASecondLineOrField() throws Exception {
        mockMvc.perform(get("/api/inventory").header(HttpHeaders.AUTHORIZATION,
                JwtTestSupport.bearerFor("77", "evil@example.com\r\n{\"event\":\"forged\"}", "USER")));

        LogCapture.Line line = capture.accessLines("/api/inventory").get(0);
        assertThat(line.text("userEmail")).startsWith("evil@example.com__");
        assertNoForgery();
    }

    @Test
    void anAuditPathWithLineBreaks_doesNotCreateASecondLineOrField() throws Exception {
        mockMvc.perform(get("/api/inventory/sku/{sku}/audit", "Books\r\n{\"event\":\"forged\"}"));

        assertNoForgery();
    }

    /**
     * No event named "forged", no user "admin", no line of the output has a raw line break (JSON escapes them), and
     * every message that carries the injected text is free of line breaks. (Messages of Hibernate's own SQL logger
     * are multi-line by design and do not carry caller values.)
     */
    private void assertNoForgery() {
        assertThat(capture.lines()).isNotEmpty();
        for (LogCapture.Line line : capture.lines()) {
            assertThat(line.text("event")).as(line.raw()).isNotEqualTo("forged");
            assertThat(line.text("userId")).as(line.raw()).isNotEqualTo("admin");
            assertThat(line.raw().stripTrailing()).as("one line per event").doesNotContain("\n").doesNotContain("\r");
            String message = line.message();
            if (message.contains("forged") || message.contains("ABC") || message.contains("Books")
                    || message.contains("LINE")) {
                assertThat(message).as(line.raw()).doesNotContain("\r").doesNotContain("\n");
            }
        }
    }
}
