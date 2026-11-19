package com.microservices.product.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks every security rule against the real application (real filter chain, real controllers,
 * in-memory database). Only the secret and the database are replaced for the test.
 */
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
        "spring.datasource.url=jdbc:h2:mem:security-rules-test;DB_CLOSE_DELAY=-1",
        "server-info.cloud-metadata.enabled=false",
        // Nothing listens here, so these tests can never call a real inventory-service.
        "product.inventory-service.url=http://localhost:1"
})
@AutoConfigureMockMvc
@AutoConfigureObservability // tests switch metrics export off by default; /actuator/prometheus needs it
class SecurityRulesTest {

    private static final String PRODUCT_JSON =
            "{\"name\":\"Security Test Phone\",\"price\":10.50,\"category\":\"Electronics\",\"skuCode\":\"SEC-TEST-1\"}";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ helpers

    private ResultActions send(String method, String path, String body, String authorization) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(builder);
    }

    private void assertPassesSecurityLayer(ResultActions result) {
        int status = result.andReturn().getResponse().getStatus();
        assertThat(status).as("status of a request that should pass the security layer").isNotIn(401, 403);
    }

    private static void expectProblem(ResultActions result, int status, String title) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.*", hasSize(4)));
    }

    /** Every write endpoint of this service, plus one path that does not exist. */
    static Stream<Arguments> writeRequests() {
        return Stream.of(
                Arguments.of("POST", "/api/products", PRODUCT_JSON),
                Arguments.of("PUT", "/api/products/999999", PRODUCT_JSON),
                Arguments.of("PUT", "/api/products/999999/images", "{\"images\":[]}"),
                Arguments.of("PATCH", "/api/products/999999", PRODUCT_JSON),
                Arguments.of("DELETE", "/api/products/999999", null),
                Arguments.of("POST", "/api/no-such-path", "{}"));
    }

    // ------------------------------------------------------------------ public reads

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/products",
            "/api/products/categories",
            "/api/products/category/Electronics/count",
            "/api/products/sku/NO-SUCH-SKU/exists",
            "/api-docs"
    })
    void get_isPublic_andReturns200(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/products/sku/NO-SUCH-SKU",
            "/api/products/999999",
            "/swagger-ui.html",
            "/swagger-ui/index.html"
    })
    void get_ofOtherPublicPaths_passesTheSecurityLayerWithoutToken(String path) throws Exception {
        assertPassesSecurityLayer(send("GET", path, null, null));
    }

    @Test
    void head_andOptions_arePublic() throws Exception {
        mockMvc.perform(request(HttpMethod.HEAD, "/api/products")).andExpect(status().isOk());
        assertPassesSecurityLayer(send("OPTIONS", "/api/products", null, null));
    }

    // ------------------------------------------------------------------ writes need an ADMIN token

    @ParameterizedTest
    @MethodSource("writeRequests")
    void write_withoutToken_returns401(String method, String path, String body) throws Exception {
        expectProblem(send(method, path, body, null), 401, "Unauthorized");
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void write_withUserToken_returns403(String method, String path, String body) throws Exception {
        expectProblem(send(method, path, body, JwtTestSupport.bearer("USER")), 403, "Forbidden");
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void write_withAdminToken_passesTheSecurityLayer(String method, String path, String body) throws Exception {
        assertPassesSecurityLayer(send(method, path, body, JwtTestSupport.bearer("ADMIN")));
    }

    @Test
    void adminCanCreateAProduct_andTheControllerAnswerIsKept() throws Exception {
        String body = PRODUCT_JSON.replace("SEC-TEST-1", "SEC-TEST-2");

        send("POST", "/api/products", body, JwtTestSupport.bearer("ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skuCode").value("SEC-TEST-2"));
    }

    @Test
    void adminRequest_thatFailsValidation_keepsTheControllerAnswer400_notA401() throws Exception {
        send("POST", "/api/products", "{\"name\":\"\"}", JwtTestSupport.bearer("ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminRequest_forAnUnknownId_keepsTheControllerAnswer404_notA401() throws Exception {
        send("DELETE", "/api/products/999999", null, JwtTestSupport.bearer("ADMIN"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ bad tokens

    static Stream<Arguments> badAuthorizationHeaders() {
        return Stream.of(
                Arguments.of("expired token", "Bearer " + JwtTestSupport.expiredToken("ADMIN")),
                Arguments.of("token signed with another secret", "Bearer " + JwtTestSupport.tokenSignedWithOtherSecret("ADMIN")),
                Arguments.of("tampered token (USER changed to ADMIN)", "Bearer " + JwtTestSupport.tamperedToAdmin()),
                Arguments.of("text that is not a token", "Bearer not-a-jwt"),
                Arguments.of("empty bearer value", "Bearer "),
                Arguments.of("another scheme", "Basic dXNlcjpwYXNzd29yZA=="));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badAuthorizationHeaders")
    void write_withBadToken_returns401(String description, String authorization) throws Exception {
        expectProblem(send("DELETE", "/api/products/999999", null, authorization), 401, "Unauthorized");
    }

    @Test
    void a401_tellsTheClientToUseABearerToken() throws Exception {
        send("DELETE", "/api/products/999999", null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void invalidToken_onAPublicRead_isAlsoRejectedWith401() throws Exception {
        // Standard resource-server behaviour: a token that is sent must be valid, even where none is needed.
        expectProblem(send("GET", "/api/products", null, "Bearer " + JwtTestSupport.expiredToken("USER")),
                401, "Unauthorized");
    }

    @Test
    void validToken_withoutRoleClaim_isForbiddenOnWrites() throws Exception {
        expectProblem(send("DELETE", "/api/products/999999", null,
                "Bearer " + JwtTestSupport.tokenWithoutRole()), 403, "Forbidden");
    }

    // ------------------------------------------------------------------ actuator

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/info",
            "/actuator/metrics",
            "/actuator/prometheus"
    })
    void actuator_healthAndOtherEndpoints_arePublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/server.port", "/actuator/loggers", "/actuator/loggers/ROOT"})
    void actuator_envAndLoggers_withoutToken_return401(String path) throws Exception {
        expectProblem(send("GET", path, null, null), 401, "Unauthorized");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/server.port", "/actuator/loggers", "/actuator/loggers/ROOT"})
    void actuator_envAndLoggers_withUserToken_return403(String path) throws Exception {
        expectProblem(send("GET", path, null, JwtTestSupport.bearer("USER")), 403, "Forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/server.port", "/actuator/loggers", "/actuator/loggers/ROOT"})
    void actuator_envAndLoggers_withAdminToken_return200(String path) throws Exception {
        send("GET", path, null, JwtTestSupport.bearer("ADMIN")).andExpect(status().isOk());
    }

    @Test
    void actuator_changingALogLevel_needsAdmin_whateverTheMethod() throws Exception {
        String body = "{\"configuredLevel\":\"INFO\"}";

        expectProblem(send("POST", "/actuator/loggers/ROOT", body, null), 401, "Unauthorized");
        expectProblem(send("POST", "/actuator/loggers/ROOT", body, JwtTestSupport.bearer("USER")), 403, "Forbidden");
        send("POST", "/actuator/loggers/ROOT", body, JwtTestSupport.bearer("ADMIN"))
                .andExpect(status().isNoContent());
    }

    @Test
    void actuator_envWithBadToken_returns401() throws Exception {
        expectProblem(send("GET", "/actuator/env", null, "Bearer " + JwtTestSupport.tamperedToAdmin()),
                401, "Unauthorized");
    }

    // ------------------------------------------------------------------ Swagger

    @Test
    void swaggerDocument_describesTheBearerScheme_soTheAuthorizeButtonWorks() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }
}
