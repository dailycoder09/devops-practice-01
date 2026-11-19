package com.microservices.order.logging;

import com.microservices.order.support.JwtTestSupport;
import com.microservices.order.support.LogCapture.Line;
import com.microservices.order.support.LoggingTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access line, the user and client fields, the trace header and the security events, through the whole service
 * (real filter order, real security chain, tracing switched on): tests 1, 2 and 3 of the logging contract and
 * Review Focus 1 to 4.
 */
class AccessLogIntegrationTest extends LoggingTestBase {

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void assertTraceIdMatchesHeader(Line line, MvcResult result) {
        String header = traceIdHeader(result);
        assertThat(header).as("X-Trace-Id").isNotBlank().matches("[0-9a-f]{16,32}");
        assertThat(line.mdc("traceId")).as("traceId in the access line").isEqualTo(header);
        assertThat(line.mdc("spanId")).isNotBlank();
    }

    // ================================================================== test 1: the access line

    @Test
    void anAnonymousCall_hasOneAccessLine_withAnonymousUser_andCoversThe401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders").with(remoteAddr("198.51.100.9")))
                .andExpect(status().isUnauthorized()).andReturn();

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.field("event")).isEqualTo("http_request");
        assertThat(line.field("httpStatus")).isEqualTo(401);
        assertThat(((Number) line.field("durationMs")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.mdc("userId")).isEqualTo("anonymous");
        assertThat(line.mdc()).doesNotContainKeys("userEmail", "userRole");
        assertThat(line.mdc("clientIp")).isEqualTo("198.51.100.9");
        assertThat(line.message()).matches("HTTP GET /api/orders -> 401 in \\d+ ms");
        assertTraceIdMatchesHeader(line, result);
    }

    @Test
    void anAuthenticatedCall_hasTheUserFields_andTheTraceId() throws Exception {
        MvcResult result = mockMvc.perform(getWith("/api/orders", userToken).with(remoteAddr("198.51.100.9")))
                .andExpect(status().isOk()).andReturn();

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.field("httpStatus")).isEqualTo(200);
        assertThat(line.field("httpMethod")).isEqualTo("GET");
        assertThat(line.field("httpPath")).isEqualTo("/api/orders");
        assertThat(line.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(line.mdc("userEmail")).isEqualTo("user" + userId + "@example.com");
        assertThat(line.mdc("userRole")).isEqualTo("USER");
        assertThat(line.mdc("clientIp")).isEqualTo("198.51.100.9");
        assertTraceIdMatchesHeader(line, result);
        assertThat(logs.events("http_request")).hasSize(1);
    }

    @Test
    void anAdmin_isLoggedWithTheAdminRole() throws Exception {
        mockMvc.perform(getWith("/api/orders", JwtTestSupport.adminToken(userId))).andExpect(status().isOk());

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.mdc("userRole")).isEqualTo("ADMIN");
        assertThat(line.mdc("userEmail")).isEqualTo("admin" + userId + "@example.com");
    }

    @Test
    void aSERVICEToken_isShownAsTheCallingService_andTheLineCoversThe403() throws Exception {
        MvcResult result = mockMvc.perform(getWith("/api/orders", JwtTestSupport.serviceToken()))
                .andExpect(status().isForbidden()).andReturn();

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.field("httpStatus")).isEqualTo(403);
        assertThat(line.mdc("userId")).isEqualTo("order-service");
        assertThat(line.mdc("userRole")).isEqualTo("SERVICE");
        assertThat(line.mdc()).doesNotContainKey("userEmail");
        assertTraceIdMatchesHeader(line, result);
    }

    @Test
    void aUserWhoIsNotAllowed_403_stillHasTheUserFieldsOnTheAccessLine() throws Exception {
        mockMvc.perform(getWith("/actuator/env", userToken)).andExpect(status().isForbidden());

        Line line = logs.accessLine("GET", "/actuator/env");
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(line.mdc("userRole")).isEqualTo("USER");
    }

    @Test
    void aRequestThatIsRejectedBecauseOfTheToken_401_isAnonymousButStillLogged() throws Exception {
        String tampered = JwtTestSupport.withTamperedRole(userToken, "ADMIN");

        MvcResult result = mockMvc.perform(getWith("/api/orders", tampered)).andExpect(status().isUnauthorized())
                .andReturn();

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.field("httpStatus")).isEqualTo(401);
        assertThat(line.mdc("userId")).isEqualTo("anonymous");
        assertThat(line.mdc()).doesNotContainKeys("userEmail", "userRole");
        assertTraceIdMatchesHeader(line, result);
    }

    @Test
    void aValidTokenWhoseSubjectIsNotANumber_401_showsTheTokenSubjectOnTheAccessLine() throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, "not-a-number", "a@b.c", "USER");

        mockMvc.perform(getWith("/api/orders", token)).andExpect(status().isUnauthorized());

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.field("httpStatus")).isEqualTo(401);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.mdc("userId")).isEqualTo("not-a-number");
        assertThat(logs.events("auth_rejected")).hasSize(1);
    }

    @Test
    void aRequestThatEndsInA404_isInfo_andAPostThatWorks_isLoggedWithItsMethod() throws Exception {
        mockMvc.perform(getWith("/api/orders/987654321", userToken)).andExpect(status().isNotFound());
        shop.addToCart(userId, "SKU-C", 1, "5.00");
        checkout(userToken).andExpect(status().isCreated());

        assertThat(logs.accessLine("GET", "/api/orders/987654321").level()).isEqualTo("INFO");
        Line post = logs.accessLine("POST", "/api/orders");
        assertThat(post.field("httpStatus")).isEqualTo(201);
        assertThat(post.level()).isEqualTo("INFO");
    }

    @Test
    void theAccessLine_isTheLastLineOfTheRequest_afterItsAuditEvents() throws Exception {
        shop.addToCart(userId, "SKU-C", 1, "5.00");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();

        List<Line> ofThisRequest = logs.lines(line -> traceIdHeader(result).equals(line.mdc("traceId")));
        assertThat(ofThisRequest).isNotEmpty();
        assertThat(ofThisRequest.get(ofThisRequest.size() - 1).isEvent("http_request")).isTrue();
        assertThat(ofThisRequest.stream().filter(line -> line.isEvent("http_request"))).hasSize(1);
        assertThat(ofThisRequest.stream().anyMatch(line -> line.isEvent("order_confirmed"))).isTrue();
    }

    // ================================================================== the client address

    @Test
    void xForwardedFor_winsOverTheSocketAddress() throws Exception {
        mockMvc.perform(getWith("/api/orders", userToken).with(remoteAddr("10.0.0.5"))
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")).andExpect(status().isOk());

        assertThat(logs.accessLine("GET", "/api/orders").mdc("clientIp")).isEqualTo("203.0.113.7");
    }

    @Test
    void anIpv6ForwardedAddress_isLogged() throws Exception {
        mockMvc.perform(getWith("/api/orders", userToken).header("X-Forwarded-For", "2001:db8::1"))
                .andExpect(status().isOk());

        assertThat(logs.accessLine("GET", "/api/orders").mdc("clientIp")).isEqualTo("2001:db8::1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip", "<script>alert(1)</script>", "1.2.3.4\r\nINFO forged log line",
            "9999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999",
            "1.2.3.4 evil"})
    void aBadForwardedValue_isIgnored_andTheSocketAddressIsUsed(String bad) throws Exception {
        mockMvc.perform(getWith("/api/orders", userToken).with(remoteAddr("10.0.0.5")).header("X-Forwarded-For", bad))
                .andExpect(status().isOk());

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.mdc("clientIp")).isEqualTo("10.0.0.5");
        assertThat(line.hasControlCharacters()).isFalse();
        assertThat(logs.allText()).doesNotContain("forged log line").doesNotContain("<script>");
    }

    // ================================================================== trace header

    @Test
    void everyRequestGetsItsOwnTraceId_inTheHeaderAndTheLog() throws Exception {
        MvcResult first = mockMvc.perform(getWith("/api/orders", userToken)).andReturn();
        MvcResult second = mockMvc.perform(getWith("/api/orders", userToken)).andReturn();

        assertThat(traceIdHeader(first)).isNotBlank().isNotEqualTo(traceIdHeader(second));
        List<Line> lines = logs.accessLines("GET", "/api/orders");
        assertThat(lines).extracting(line -> line.mdc("traceId"))
                .containsExactly(traceIdHeader(first), traceIdHeader(second));
    }

    @Test
    void anIncomingTraceparent_isContinued() throws Exception {
        String incoming = "4bf92f3577b34da6a3ce929d0e0e4736";
        MvcResult result = mockMvc.perform(getWith("/api/orders", userToken)
                .header("traceparent", "00-" + incoming + "-00f067aa0ba902b7-01")).andReturn();

        assertThat(normalizeTraceId(traceIdHeader(result))).isEqualTo(incoming);
        assertThat(logs.accessLine("GET", "/api/orders").mdc("traceId")).isEqualTo(traceIdHeader(result));
    }

    @Test
    void theTraceHeader_isAlsoOnTheErrorAnswers() throws Exception {
        MvcResult unauthorized = mockMvc.perform(get("/api/orders")).andReturn();
        MvcResult notFound = mockMvc.perform(getWith("/api/orders/123456789", userToken)).andReturn();
        MvcResult badRequest = mockMvc.perform(getWith("/api/orders/abc", userToken)).andReturn();

        for (MvcResult result : List.of(unauthorized, notFound, badRequest)) {
            assertThat(traceIdHeader(result)).isNotBlank();
        }
        assertThat(badRequest.getResponse().getStatus()).isEqualTo(400);
    }

    // ================================================================== what is never logged

    @Test
    void theQueryString_isNeverLogged() throws Exception {
        mockMvc.perform(getWith("/api/orders?page=0&size=5&secret=TOPSECRET-VALUE&email=probe@example.com", userToken))
                .andExpect(status().isOk());

        assertThat(logs.accessLine("GET", "/api/orders").field("httpPath")).isEqualTo("/api/orders");
        assertThat(logs.allText()).doesNotContain("TOPSECRET-VALUE").doesNotContain("probe@example.com")
                .doesNotContain("secret=");
    }

    @Test
    void aBadQueryParameter_400_isLoggedWithoutItsValue() throws Exception {
        mockMvc.perform(getWith("/api/orders?size=SENSITIVE-BAD-VALUE", userToken)).andExpect(status().isBadRequest());

        assertThat(logs.accessLine("GET", "/api/orders").field("httpStatus")).isEqualTo(400);
        assertThat(logs.allText()).doesNotContain("SENSITIVE-BAD-VALUE");
    }

    // ================================================================== test 6: log injection

    @Test
    void aLiteralLineBreakInThePath_doesNotMakeASecondLineOrField() throws Exception {
        RequestPostProcessor literalPath = request -> {
            request.setRequestURI("/api/orders/x\r\nINFO forged access line");
            return request;
        };

        mockMvc.perform(getWith("/api/orders/1", userToken).with(literalPath));

        List<Line> lines = logs.events("http_request");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).hasControlCharacters()).isFalse();
        assertThat((String) lines.get(0).field("httpPath")).startsWith("/api/orders/x__INFO forged access line");
    }

    @Test
    void anEncodedLineBreakInThePath_staysAsHarmlessText() throws Exception {
        mockMvc.perform(get(URI.create("/api/orders/x%0D%0AINFO%20forged")).header(HttpHeaders.AUTHORIZATION,
                bearer(userToken)));

        assertThat(logs.events("http_request")).hasSize(1);
        assertThat(logs.ownLines().stream().filter(Line::hasControlCharacters)).isEmpty();
    }

    @Test
    void anEmailWithLineBreaksInTheToken_isCleanedInEveryLine() throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, String.valueOf(userId),
                "evil@example.com\r\nINFO forged line " + "z".repeat(400), "USER");

        mockMvc.perform(getWith("/api/orders", token)).andExpect(status().isOk());

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.mdc("userEmail")).startsWith("evil@example.com__INFO forged line").hasSizeLessThanOrEqualTo(200);
        assertThat(line.hasControlCharacters()).isFalse();
        assertThat(logs.ownLines().stream().filter(Line::hasControlCharacters)).isEmpty();
    }

    // ================================================================== the security events

    @Test
    void noToken_logsAuthRejected_missingToken_atWarn() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")).andExpect(status().isUnauthorized()).andReturn();

        Line event = logs.single("auth_rejected");
        assertThat(event.level()).isEqualTo("WARN");
        assertThat(event.field("reason")).isEqualTo("missing_token");
        assertThat(event.field("httpMethod")).isEqualTo("POST");
        assertThat(event.field("httpPath")).isEqualTo("/api/orders");
        assertThat(event.mdc("traceId")).isEqualTo(traceIdHeader(result));
    }

    @Test
    void aBadToken_logsAuthRejected_invalidToken_andNeverTheToken() throws Exception {
        String tampered = JwtTestSupport.withTamperedRole(userToken, "ADMIN");

        mockMvc.perform(getWith("/api/orders", tampered)).andExpect(status().isUnauthorized());

        Line event = logs.single("auth_rejected");
        assertThat(event.level()).isEqualTo("WARN");
        assertThat(event.field("reason")).isEqualTo("invalid_token");
        assertThat(logs.allText()).doesNotContain(tampered).doesNotContain(tampered.split("\\.")[2])
                .doesNotContain(tampered.split("\\.")[1]);
    }

    @Test
    void anExpiredToken_andGarbage_areInvalidToken() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        String expired = JwtTestSupport.token(JwtTestSupport.TEST_SECRET, String.valueOf(userId), "a@b.c", "USER",
                now.minusSeconds(7200), now.minusSeconds(3600));

        mockMvc.perform(getWith("/api/orders", expired)).andExpect(status().isUnauthorized());
        mockMvc.perform(getWith("/api/orders", "garbage")).andExpect(status().isUnauthorized());

        assertThat(logs.events("auth_rejected")).hasSize(2)
                .allSatisfy(event -> assertThat(event.field("reason")).isEqualTo("invalid_token"));
    }

    @Test
    void aWrongRole_logsAccessDenied_atWarn_withMethodAndPath() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION,
                bearer(JwtTestSupport.serviceToken()))).andExpect(status().isForbidden()).andReturn();

        Line event = logs.single("access_denied");
        assertThat(event.level()).isEqualTo("WARN");
        assertThat(event.field("httpMethod")).isEqualTo("POST");
        assertThat(event.field("httpPath")).isEqualTo("/api/orders");
        assertThat(event.mdc("userId")).isEqualTo("order-service");
        assertThat(event.mdc("userRole")).isEqualTo("SERVICE");
        assertThat(event.mdc("traceId")).isEqualTo(traceIdHeader(result));
        assertThat(logs.events("auth_rejected")).isEmpty();
    }

    @Test
    void aSuccessfulCall_logsNoSecurityEvent() throws Exception {
        mockMvc.perform(getWith("/api/orders", userToken)).andExpect(status().isOk());

        assertThat(logs.events("auth_rejected")).isEmpty();
        assertThat(logs.events("access_denied")).isEmpty();
    }

    // ================================================================== test 2 and Review Focus 3: health

    @Test
    void aHealthCheck_isDebug_andStillWorksWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        List<Line> lines = logs.events("http_request");
        assertThat(lines).hasSize(3).allSatisfy(line -> {
            assertThat(line.level()).isEqualTo("DEBUG");
            assertThat(line.field("httpStatus")).isEqualTo(200);
            assertThat(line.mdc("userId")).isEqualTo("anonymous");
        });
        assertThat(logs.lines(line -> "INFO".equals(line.level()) || "WARN".equals(line.level())
                || "ERROR".equals(line.level()))).isEmpty();
    }

    @Test
    void aMetricsScrape_isDebug_butAnAdminActionOnTheLoggersIsInfo() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mockMvc.perform(getWith("/actuator/loggers/ROOT", JwtTestSupport.adminToken(userId))).andExpect(status().isOk());

        assertThat(logs.accessLine("GET", "/actuator/metrics").level()).isEqualTo("DEBUG");
        assertThat(logs.accessLine("GET", "/actuator/loggers/ROOT").level()).isEqualTo("INFO");
    }

    // ================================================================== test 3: the MDC is clean afterwards

    @Test
    void theMdc_isEmptyAgain_afterEveryKindOfRequest() throws Exception {
        mockMvc.perform(get("/api/orders"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        mockMvc.perform(getWith("/api/orders", userToken));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        mockMvc.perform(getWith("/api/orders", JwtTestSupport.serviceToken()));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        mockMvc.perform(getWith("/api/orders", JwtTestSupport.withTamperedRole(userToken, "ADMIN")));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        mockMvc.perform(getWith("/api/orders/abc", userToken));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        shop.addToCart(userId, "SKU-C", 1, "5.00");
        checkout(userToken);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        mockMvc.perform(get("/actuator/health"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aSecondRequestOnTheSameThread_doesNotInheritTheFirstUser() throws Exception {
        mockMvc.perform(getWith("/api/orders", userToken).with(remoteAddr("198.51.100.1")));
        mockMvc.perform(get("/api/orders").with(remoteAddr("198.51.100.2")));

        List<Line> lines = logs.accessLines("GET", "/api/orders");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(lines.get(1).mdc("userId")).isEqualTo("anonymous");
        assertThat(lines.get(1).mdc()).doesNotContainKeys("userEmail", "userRole");
        assertThat(lines.get(1).mdc("clientIp")).isEqualTo("198.51.100.2");
        assertThat(lines.get(1).mdc("traceId")).isNotEqualTo(lines.get(0).mdc("traceId"));
    }
}
