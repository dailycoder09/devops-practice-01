package com.microservices.order.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.order.service.ServiceTokenIssuer;
import com.microservices.order.support.CheckoutTestBase;
import com.microservices.order.support.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access rules: which URLs are public, which need a USER or ADMIN login, which need ADMIN,
 * what a SERVICE token may not do, and what the 401 and 403 answers look like (Review Focus 1).
 */
// Turns the metrics export back on (tests switch it off), so /actuator/prometheus exists here as it does when running.
@AutoConfigureObservability
class SecurityRulesTest extends CheckoutTestBase {

    @Autowired
    private ServiceTokenIssuer serviceTokenIssuer;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    /** The three calls of the order API, built for one token (or none when the token is null). */
    private List<MockHttpServletRequestBuilder> orderCalls(String token) {
        List<MockHttpServletRequestBuilder> calls = List.of(get("/api/orders"), get("/api/orders/1"),
                post("/api/orders"));
        if (token != null) {
            calls.forEach(call -> call.header(HttpHeaders.AUTHORIZATION, bearer(token)));
        }
        return calls;
    }

    // ================================================================== public URLs

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void health_isPublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator/metrics", "/actuator/prometheus"})
    void otherActuatorEndpoints_arePublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void openApiDocument_isPublic_andDescribesTheBearerLogin() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void swaggerPages_arePublic() throws Exception {
        int redirect = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(redirect).isIn(200, 302);
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    // ================================================================== /api/orders needs USER or ADMIN

    @Test
    void orderApi_return401_withoutToken() throws Exception {
        for (MockHttpServletRequestBuilder call : orderCalls(null)) {
            mockMvc.perform(call)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            org.hamcrest.Matchers.startsWith("Bearer")));
        }
        assertThat(shop.cartServer.requests()).isEmpty();
    }

    @Test
    void orderApi_isOpenToAUserToken_andToAnAdminToken() throws Exception {
        for (String token : List.of(userToken, JwtTestSupport.adminToken(userId))) {
            mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/orders/1").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aSERVICEToken_gets403_onEveryOrderCall_andNoServiceIsCalled() throws Exception {
        // The real token, made by the real issuer: the one a browser could never get but another service could.
        String token = serviceTokenIssuer.issue();
        for (MockHttpServletRequestBuilder call : orderCalls(token)) {
            mockMvc.perform(call)
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.title").value("Forbidden"))
                    .andExpect(jsonPath("$.status").value(403));
        }
        assertThat(shop.cartServer.requests()).isEmpty();
        assertThat(orderCount(userId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GUEST", "user", ""})
    void aTokenWithAnotherRole_gets403(String role) throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, String.valueOf(userId), "a@b.c", role);
        for (MockHttpServletRequestBuilder call : orderCalls(token)) {
            mockMvc.perform(call).andExpect(status().isForbidden());
        }
    }

    @Test
    void aTokenWithoutARole_gets403() throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, String.valueOf(userId), "a@b.c", null);
        for (MockHttpServletRequestBuilder call : orderCalls(token)) {
            mockMvc.perform(call).andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- a user id that is not a number: 401, never 500

    @ParameterizedTest
    @ValueSource(strings = {"abc", "order-service", "12abc", "1.5", "", " ", "99999999999999999999999", "0x10"})
    void aNonNumericSubject_gets401_onEveryOrderCall_forUserAndAdmin(String subject) throws Exception {
        for (String role : List.of("USER", "ADMIN")) {
            String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, subject, "a@b.c", role);
            for (MockHttpServletRequestBuilder call : orderCalls(token)) {
                mockMvc.perform(call)
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                        .andExpect(jsonPath("$.title").value("Unauthorized"))
                        .andExpect(jsonPath("$.status").value(401));
            }
        }
        // It stops before any other service is called and before anything is saved.
        assertThat(shop.cartServer.requests()).isEmpty();
        assertThat(shop.productServer.requests()).isEmpty();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @Test
    void aTokenWithoutASubject_gets401() throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, null, "a@b.c", "USER");
        for (MockHttpServletRequestBuilder call : orderCalls(token)) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
    }

    // ---------------------------------------------------------------- bad tokens

    @Test
    void anExpiredToken_gets401() throws Exception {
        Instant now = Instant.now();
        String token = JwtTestSupport.token(JwtTestSupport.TEST_SECRET, String.valueOf(userId), "a@b.c", "USER",
                now.minusSeconds(7200), now.minusSeconds(3600));
        for (MockHttpServletRequestBuilder call : orderCalls(token)) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aTokenSignedWithAnotherSecret_gets401() throws Exception {
        String token = JwtTestSupport.validToken("another-secret-that-is-long-enough-for-hs256-0123456789",
                String.valueOf(userId), "a@b.c", "USER");
        for (MockHttpServletRequestBuilder call : orderCalls(token)) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anUnsignedToken_andGarbage_get401() throws Exception {
        for (String token : List.of(JwtTestSupport.unsigned(String.valueOf(userId), "a@b.c", "ADMIN"), "nope",
                "a.b.c")) {
            for (MockHttpServletRequestBuilder call : orderCalls(token)) {
                mockMvc.perform(call).andExpect(status().isUnauthorized());
            }
        }
    }

    @Test
    void aSERVICETokenWithItsRoleChangedToUSER_isRejected_becauseTheSignatureNoLongerMatches() throws Exception {
        String tampered = JwtTestSupport.withTamperedRole(serviceTokenIssuer.issue(), "USER");

        for (MockHttpServletRequestBuilder call : orderCalls(tampered)) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
    }

    // ================================================================== other methods and URLs

    @Test
    void methodsThatTheApiDoesNotHave_answer405_asProblemJson() throws Exception {
        mockMvc.perform(delete("/api/orders/1").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(put("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void methodsThatTheApiDoesNotHave_stillNeedALogin() throws Exception {
        mockMvc.perform(delete("/api/orders/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/orders/1").header(HttpHeaders.AUTHORIZATION, bearer(serviceTokenIssuer.issue())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownUrls_return401WithoutToken_and404WithAToken() throws Exception {
        mockMvc.perform(get("/api/other")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/other").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ================================================================== admin-only actuator URLs

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/spring.application.name", "/actuator/loggers",
            "/actuator/loggers/ROOT"})
    void envAndLoggers_return401WithoutToken_403ForUserAndService_200ForAdmin(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(serviceTokenIssuer.issue())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(JwtTestSupport.adminToken(userId))))
                .andExpect(status().isOk());
    }

    @Test
    void loggers_cannotBeChanged_withoutAdmin() throws Exception {
        String change = "{\"configuredLevel\":\"DEBUG\"}";

        mockMvc.perform(post("/actuator/loggers/ROOT").contentType("application/json").content(change))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/actuator/loggers/ROOT").contentType("application/json").content(change)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void env_neverShowsTheSecret_evenToAnAdmin() throws Exception {
        String envJson = mockMvc.perform(get("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, bearer(JwtTestSupport.adminToken(userId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(envJson).doesNotContain(jwtSecret);
    }

    // ================================================================== what 401 and 403 look like

    @Test
    void unauthorizedAnswer_isProblemJson_withTheContractFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("detail").asText()).isNotBlank();
    }

    @Test
    void forbiddenAnswer_isProblemJson_withTheContractFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Forbidden");
        assertThat(problem.get("status").asInt()).isEqualTo(403);
        assertThat(problem.get("detail").asText()).isNotBlank();
    }

    @Test
    void theAnswersNeverShowTheTokenOrTheSecret() throws Exception {
        String badToken = JwtTestSupport.withTamperedRole(userToken, "ADMIN");

        String body = mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(badToken)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(badToken).doesNotContain(jwtSecret);
    }

    // ================================================================== stateless

    @Test
    void service_isStateless_noSessionAndNoCookies() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void headAndOptions_onTheOrderApi_alsoNeedALogin() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(HttpMethod.HEAD, "/api/orders"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(HttpMethod.OPTIONS, "/api/orders"))
                .andExpect(status().isUnauthorized());
    }
}
