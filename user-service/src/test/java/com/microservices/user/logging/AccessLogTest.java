package com.microservices.user.logging;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.user.support.AbstractLoggingTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access line through the real filter chain: who, from where, which trace, which answer.
 */
class AccessLogTest extends AbstractLoggingTest {

    private static final String FILTER_LOGGER = RequestLoggingFilter.class.getName();

    // ---------------------------------------------------------------- test 1: the access line

    @Test
    void anonymousCall_writesOneAccessLine_withTheContractFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/info")).andExpect(status().isOk()).andReturn();

        JsonNode line = logs.singleAccessLine("/actuator/info");
        assertThat(line.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(line.get("userId").asText()).isEqualTo("anonymous");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(line.has("userRole")).isFalse();
        assertThat(line.get("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(line.get("traceId").asText()).matches("[0-9a-f]{16,32}");
        assertThat(line.get("spanId").asText()).matches("[0-9a-f]{16}");
        assertThat(line.get("message").asText()).matches("HTTP GET /actuator/info -> 200 in \\d+ ms");
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(line.get("traceId").asText());
    }

    @Test
    void authenticatedCall_accessLineHasTheUserFields() throws Exception {
        String email = uniqueEmail();
        long id = register(email);
        String token = login(email);

        MvcResult result = getWithToken("/api/users/me", token).andExpect(status().isOk()).andReturn();

        JsonNode line = logs.singleAccessLine("/api/users/me");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(line.get("userId").asText()).isEqualTo(String.valueOf(id));
        assertThat(line.get("userEmail").asText()).isEqualTo(email);
        assertThat(line.get("userRole").asText()).isEqualTo("USER");
        assertThat(line.get("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(line.get("traceId").asText()).isNotBlank();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(line.get("traceId").asText());
    }

    @Test
    void forwardedFor_winsOverTheSocketAddress() throws Exception {
        mockMvc.perform(get("/actuator/info").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(status().isOk());

        assertThat(logs.singleAccessLine("/actuator/info").get("clientIp").asText()).isEqualTo("203.0.113.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip-address", "999999999999999999999999999999999999999999999999999999999999"})
    void aBadForwardedFor_isIgnored(String bad) throws Exception {
        mockMvc.perform(get("/actuator/info").header("X-Forwarded-For", bad)).andExpect(status().isOk());

        JsonNode line = logs.singleAccessLine("/actuator/info");
        assertThat(line.get("clientIp").asText()).isEqualTo("127.0.0.1");
    }

    @Test
    void theQueryString_isNotLogged() throws Exception {
        mockMvc.perform(get("/actuator/info?token=SECRET123&q=somebody@example.com")).andExpect(status().isOk());

        assertThat(logs.singleAccessLine("/actuator/info")).isNotNull();
        assertThat(logs.allText()).doesNotContain("SECRET123").doesNotContain("somebody@example.com");
    }

    @Test
    void anIncomingTraceHeader_isContinued_soOneTraceCanSpanServices() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        MvcResult result = mockMvc.perform(get("/actuator/info")
                        .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(traceId);
        assertThat(logs.singleAccessLine("/actuator/info").get("traceId").asText()).isEqualTo(traceId);
    }

    // ---------------------------------------------------------------- test 2: levels

    @Test
    void healthCheck_isDebug_notInfo() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        assertThat(logs.accessLines("/actuator/health")).isEmpty();

        logs.level(FILTER_LOGGER, Level.DEBUG);
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

        assertThat(logs.singleAccessLine("/actuator/health").get("level").asText()).isEqualTo("DEBUG");
        assertThat(logs.singleAccessLine("/actuator/health/liveness").get("level").asText()).isEqualTo("DEBUG");
    }

    @Test
    void missingToken_is401_loggedAsWarn_withTheAnonymousUser() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());

        JsonNode line = logs.singleAccessLine("/api/users/me");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(401);
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("userId").asText()).isEqualTo("anonymous");
        assertThat(line.get("traceId").asText()).isNotBlank();
    }

    @Test
    void anInvalidToken_is401_loggedAsWarn_withTheAnonymousUser_andTheTokenIsNotLogged() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token-value-123"))
                .andExpect(status().isUnauthorized());

        JsonNode line = logs.singleAccessLine("/api/users/me");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(401);
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("userId").asText()).isEqualTo("anonymous");
        assertThat(logs.allText()).doesNotContain("garbage-token-value-123");
    }

    @Test
    void forbidden_is403_loggedAsWarn_andTheLineStillNamesTheUser() throws Exception {
        String email = uniqueEmail();
        long id = register(email);
        String token = login(email);

        getWithToken("/actuator/env", token).andExpect(status().isForbidden());

        JsonNode line = logs.singleAccessLine("/actuator/env");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(403);
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("userId").asText()).isEqualTo(String.valueOf(id));
        assertThat(line.get("userEmail").asText()).isEqualTo(email);
        assertThat(line.get("userRole").asText()).isEqualTo("USER");
    }

    @Test
    void anUnexpectedFailure_is500_loggedAsError_andTheExceptionIsLoggedOnceWithItsStackTrace() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = login(email);

        getWithToken("/test-only/boom", token).andExpect(status().isInternalServerError());

        JsonNode line = logs.singleAccessLine("/test-only/boom");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(500);
        assertThat(line.get("level").asText()).isEqualTo("ERROR");
        assertThat(line.has("stack_trace")).isFalse();

        List<JsonNode> withTrace = logs.all().stream().filter(node -> node.has("stack_trace")).toList();
        assertThat(withTrace).hasSize(1);
        assertThat(withTrace.get(0).get("level").asText()).isEqualTo("ERROR");
        assertThat(withTrace.get(0).get("stack_trace").asText()).contains("IllegalStateException")
                .contains("boom: something unexpected broke");
        assertThat(withTrace.get(0).get("userId").asText()).isNotBlank();
    }

    @Test
    void aHandledClientError_isWarn_withoutAStackTrace_andNeverShowsThePassword() throws Exception {
        postJson("/api/users/register", uniqueEmail(), "pw-7chr").andExpect(status().isBadRequest());

        List<JsonNode> handled = logs.all().stream()
                .filter(node -> node.path("logger_name").asText().endsWith("GlobalExceptionHandler")).toList();
        assertThat(handled).hasSize(1);
        assertThat(handled.get(0).get("level").asText()).isEqualTo("WARN");
        assertThat(handled.get(0).has("stack_trace")).isFalse();
        assertThat(handled.get(0).get("message").asText()).contains("password");
        assertThat(logs.allText()).doesNotContain("pw-7chr");
    }

    @Test
    void anUnknownUrl_isAnInfoLine_with404() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = login(email);

        getWithToken("/api/users/does-not-exist", token).andExpect(status().isNotFound());

        JsonNode line = logs.singleAccessLine("/api/users/does-not-exist");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(404);
        assertThat(line.get("level").asText()).isEqualTo("INFO");
    }

    // ---------------------------------------------------------------- the security handlers

    @Test
    void noToken_logsAuthRejected_missingToken() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());

        JsonNode event = logs.single("auth_rejected");
        assertThat(event.get("level").asText()).isEqualTo("WARN");
        assertThat(event.get("reason").asText()).isEqualTo("missing_token");
        assertThat(event.get("userId").asText()).isEqualTo("anonymous");
    }

    @Test
    void badToken_logsAuthRejected_invalidToken_neverTheToken() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer another-bad-token-456"))
                .andExpect(status().isUnauthorized());

        JsonNode event = logs.single("auth_rejected");
        assertThat(event.get("level").asText()).isEqualTo("WARN");
        assertThat(event.get("reason").asText()).isEqualTo("invalid_token");
        assertThat(logs.allText()).doesNotContain("another-bad-token-456");
    }

    @Test
    void forbidden_logsAccessDenied_withMethodAndPath() throws Exception {
        String email = uniqueEmail();
        long id = register(email);
        String token = login(email);

        getWithToken("/actuator/loggers", token).andExpect(status().isForbidden());

        JsonNode event = logs.single("access_denied");
        assertThat(event.get("level").asText()).isEqualTo("WARN");
        assertThat(event.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(event.get("httpPath").asText()).isEqualTo("/actuator/loggers");
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(id));
    }

    // ---------------------------------------------------------------- test 3: the MDC is empty afterwards

    @Test
    void mdc_isEmptyAgain_afterEveryKindOfRequest() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = login(email);

        getWithToken("/api/users/me", token).andExpect(status().isOk());
        assertNoMdcKeys();
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        assertNoMdcKeys();
        getWithToken("/actuator/env", token).andExpect(status().isForbidden());
        assertNoMdcKeys();
        getWithToken("/test-only/boom", token).andExpect(status().isInternalServerError());
        assertNoMdcKeys();
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
        assertNoMdcKeys();
    }

    @Test
    void aRequestAfterAnAuthenticatedOne_doesNotInheritItsUser() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = login(email);
        getWithToken("/api/users/me", token).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());

        JsonNode line = logs.singleAccessLine("/actuator/info");
        assertThat(line.get("userId").asText()).isEqualTo("anonymous");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(line.has("userRole")).isFalse();
    }

    private static void assertNoMdcKeys() {
        for (String key : new String[] {"clientIp", "userId", "userEmail", "userRole", "traceId", "spanId"}) {
            assertThat(MDC.get(key)).as("MDC key " + key).isNull();
        }
    }

    // ---------------------------------------------------------------- test 6: log injection

    @Test
    void aPathWithLineBreaks_makesNoSecondLineAndNoNewField() throws Exception {
        try {
            mockMvc.perform(get("/actuator/info").with(request -> {
                ((MockHttpServletRequest) request).setRequestURI("/actuator/info\r\n{\"level\":\"ERROR\",\"event\":\"fake\"}");
                return request;
            }));
        } catch (Exception rejected) {
            // The firewall may refuse such a path. The request is still logged, which is what is checked here.
        }

        List<JsonNode> access = logs.events("http_request");
        assertThat(access).hasSize(1);
        assertThat(access.get(0).get("httpPath").asText()).startsWith("/actuator/info__");
        assertThat(access.get(0).get("httpPath").asText()).doesNotContain("\n").doesNotContain("\r");
        assertThat(access.get(0).get("event").asText()).isEqualTo("http_request");
        assertThat(logs.events("fake")).isEmpty();
        assertThat(logs.all().stream().filter(node -> node.has("event")).filter(node -> "fake".equals(node.get("event").asText()))).isEmpty();
    }

    @Test
    void anEmailWithLineBreaks_inALogin_makesNoSecondLineAndNoNewField() throws Exception {
        String attack = "victim@example.com\r\n{\"event\":\"login_succeeded\",\"level\":\"ERROR\"}";

        postJson("/api/users/login", attack, "whatever-password").andExpect(status().isUnauthorized());

        JsonNode failed = logs.single("login_failed");
        assertThat(failed.get("email").asText()).startsWith("victim@example.com__").doesNotContain("\n")
                .doesNotContain("\r");
        assertThat(failed.get("level").asText()).isEqualTo("WARN");
        assertThat(logs.events("login_succeeded")).isEmpty();
        assertThat(logs.allText()).doesNotContain("\\r\\n{\\\"event\\\":\\\"login_succeeded");
    }
}
