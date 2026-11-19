package com.microservices.cart.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.support.AbstractCartApiTest;
import com.microservices.cart.support.JwtTestSupport;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the access rules of the service: which URLs are public, which need a login with the role USER or
 * ADMIN, which need ADMIN, and what the 401 and 403 answers look like. A SERVICE token must get 403 on the
 * cart, and a token with a bad "sub" must get 401, never a 500.
 */
class SecurityRulesTest extends AbstractCartApiTest {

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void createTokens() {
        PRODUCTS.addProduct("SEC-1", "Security Test Item", "5.00");
        userToken = TestTokens.user(TestTokens.newUserId());
        adminToken = TestTokens.admin(TestTokens.newUserId());
    }

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

    /** Every cart request: method, path, body. */
    static Stream<Arguments> cartRequests() {
        return Stream.of(
                Arguments.of("GET", "/api/cart", null),
                Arguments.of("PUT", "/api/cart/items/SEC-1", "{\"quantity\":1}"),
                Arguments.of("DELETE", "/api/cart/items/SEC-1", null),
                Arguments.of("DELETE", "/api/cart", null));
    }

    // ---------------------------------------------------------------- public URLs

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
    void openApiDocument_isPublic() throws Exception {
        mockMvc.perform(get("/api-docs")).andExpect(status().isOk());
    }

    @Test
    void swaggerPages_arePublic() throws Exception {
        int redirect = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(redirect).isIn(200, 302);
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void openApiDocument_describesTheBearerLoginForTheAuthorizeButton() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    // ---------------------------------------------------------------- admin-only actuator URLs

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/spring.application.name", "/actuator/loggers",
            "/actuator/loggers/ROOT"})
    void envAndLoggers_return401_withoutToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/spring.application.name", "/actuator/loggers",
            "/actuator/loggers/ROOT"})
    void envAndLoggers_return403_forAUserToken_andForAServiceToken(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.service())))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/spring.application.name", "/actuator/loggers",
            "/actuator/loggers/ROOT"})
    void envAndLoggers_return200_forAnAdminToken(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void loggers_cannotBeChanged_withoutAdmin() throws Exception {
        String change = "{\"configuredLevel\":\"DEBUG\"}";

        mockMvc.perform(post("/actuator/loggers/ROOT").contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/actuator/loggers/ROOT").contentType(MediaType.APPLICATION_JSON).content(change)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/actuator/loggers/ROOT").contentType(MediaType.APPLICATION_JSON).content(change)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.service())))
                .andExpect(status().isForbidden());
    }

    @Test
    void loggers_canBeChanged_byAnAdmin_whoseJsonIsReadStrictly() throws Exception {
        // a harmless logger name; the level is set and then cleared again
        mockMvc.perform(post("/actuator/loggers/com.example.harmless").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"DEBUG\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(adminToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/actuator/loggers/com.example.harmless").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":null}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void env_neverShowsTheSecret_evenToAnAdmin() throws Exception {
        String envJson = mockMvc.perform(get("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(envJson).doesNotContain(jwtSecret);
    }

    // ---------------------------------------------------------------- the cart needs USER or ADMIN

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns401_withoutToken(String method, String path, String body) throws Exception {
        send(method, path, body, null).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns200or204_forAUserToken_andForAnAdminToken(String method, String path, String body)
            throws Exception {
        for (String token : new String[] {userToken, adminToken}) {
            int status = send(method, path, body, TestTokens.bearer(token)).andReturn().getResponse().getStatus();
            assertThat(status).as(method + " " + path).isIn(200, 204);
        }
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns403_forAServiceToken(String method, String path, String body) throws Exception {
        expectProblem(send(method, path, body, TestTokens.bearer(TestTokens.service())), 403, "Forbidden");
        assertThat(PRODUCTS.requests()).as("a refused request must not reach product-service").isEmpty();
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns403_forAnyOtherRoleOrNoRole(String method, String path, String body) throws Exception {
        for (String role : new String[] {"GUEST", "user", "SERVICE", "", "ROLE_USER"}) {
            String token = TestTokens.withRole("55", role);
            send(method, path, body, TestTokens.bearer(token)).andExpect(status().isForbidden());
        }
    }

    @Test
    void cart_returns403_forAValidTokenWithoutARoleClaim() throws Exception {
        String noRole = noRoleToken("55");

        send("GET", "/api/cart", null, TestTokens.bearer(noRole)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns401_forAServiceTokenWhoseRoleWasChangedToUserAfterSigning(String method, String path, String body)
            throws Exception {
        String tampered = JwtTestSupport.withTamperedRole(TestTokens.service(), "USER");

        send(method, path, body, TestTokens.bearer(tampered)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns401_forABadToken(String method, String path, String body) throws Exception {
        Instant now = Instant.now();
        String[] badTokens = {
                "nope",
                "",
                // expired five minutes ago (more than the 60 seconds of allowed clock difference)
                JwtTestSupport.token(TestTokens.SECRET, "55", "a@b.c", "USER", now.minusSeconds(3600),
                        now.minusSeconds(300)),
                // signed with another secret
                JwtTestSupport.validToken("another-secret-that-is-long-enough-for-hs256-0123456789", "55", "a@b.c",
                        "USER"),
                // role changed after signing
                JwtTestSupport.withTamperedRole(userToken, "ADMIN"),
                // no signature at all
                JwtTestSupport.unsigned("55", "a@b.c", "USER")
        };
        for (String token : badTokens) {
            send(method, path, body, "Bearer " + token).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns401_forOtherAuthorizationSchemes(String method, String path, String body) throws Exception {
        send(method, path, body, "Basic dXNlcjpwYXNz").andExpect(status().isUnauthorized());
        send(method, path, body, userToken).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- a "sub" that is not a user id

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns401_whenSubIsNotANumber(String method, String path, String body) throws Exception {
        for (String sub : new String[] {"abc", "order-service", "12abc", "1.5", " 7", "", "99999999999999999999",
                "0x10", "1,5", "NaN"}) {
            String token = TestTokens.withRole(sub, "USER");
            expectProblem(send(method, path, body, TestTokens.bearer(token)), 401, "Unauthorized");
        }
    }

    @ParameterizedTest
    @MethodSource("cartRequests")
    void cart_returns401_whenSubIsMissing(String method, String path, String body) throws Exception {
        String noSub = tokenWithoutSub("USER");

        expectProblem(send(method, path, body, TestTokens.bearer(noSub)), 401, "Unauthorized");
    }

    @Test
    void aNonNumericSub_with_anAdminRole_alsoGives401() throws Exception {
        String token = TestTokens.withRole("not-a-number", "ADMIN");

        send("GET", "/api/cart", null, TestTokens.bearer(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonNumericSub_neverReachesProductServiceOrTheDatabase() throws Exception {
        String token = TestTokens.withRole("abc", "USER");

        send("PUT", "/api/cart/items/SEC-1", "{\"quantity\":1}", TestTokens.bearer(token))
                .andExpect(status().isUnauthorized());

        assertThat(PRODUCTS.requests()).isEmpty();
    }

    @Test
    void aNonNumericSub_isRefusedBeforeTheBodyIsLookedAt() throws Exception {
        String token = TestTokens.withRole("abc", "USER");

        send("PUT", "/api/cart/items/SEC-1", "{\"quantity\":0}", TestTokens.bearer(token))
                .andExpect(status().isUnauthorized());
        send("PUT", "/api/cart/items/SEC-1", "not json", TestTokens.bearer(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aServiceTokenIsRefusedWithA403_evenWithABadBody() throws Exception {
        send("PUT", "/api/cart/items/SEC-1", "{\"quantity\":0}", TestTokens.bearer(TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- everything else needs a login

    @Test
    void unknownUrls_return401_withoutToken() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/cart/something-else")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUrl_withAValidToken_isA404_notA401_andNotA500() throws Exception {
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cartUrlsThatDoNotExist_with_aUserToken_are404_andWithAServiceToken_403() throws Exception {
        mockMvc.perform(get("/api/cart/items").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userToken)))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/cart/items").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(TestTokens.service())))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongMethodsOnTheCart_areA405_notA500() throws Exception {
        send("POST", "/api/cart", "{}", TestTokens.bearer(userToken)).andExpect(status().isMethodNotAllowed());
        send("PATCH", "/api/cart/items/SEC-1", "{\"quantity\":1}", TestTokens.bearer(userToken))
                .andExpect(status().isMethodNotAllowed());
        send("GET", "/api/cart/items/SEC-1", null, TestTokens.bearer(userToken))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------- what 401 and 403 look like

    @Test
    void unauthorizedAnswer_isProblemJson_withTheContractFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("detail").asText()).isNotBlank();
    }

    @Test
    void invalidTokenAnswer_isTheSameProblemJson() throws Exception {
        expectProblem(mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer nope")),
                401, "Unauthorized");
    }

    @Test
    void forbiddenAnswer_isProblemJson_withTheContractFields() throws Exception {
        expectProblem(mockMvc.perform(get("/api/cart")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.service()))), 403, "Forbidden");
    }

    @Test
    void nonNumericSubAnswer_isTheSameProblemJsonAsAnyOther401() throws Exception {
        String token = TestTokens.withRole("abc", "USER");

        MvcResult result = mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("detail").asText()).isNotBlank().doesNotContain("abc");
    }

    // ---------------------------------------------------------------- stateless, no cookies

    @Test
    void service_isStateless_noSessionAndNoCookies() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private static void expectProblem(ResultActions result, int status, String title) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    private static String noRoleToken(String sub) {
        return rawToken(sub, null);
    }

    private static String tokenWithoutSub(String role) {
        return rawToken(null, role);
    }

    private static String rawToken(String sub, String role) {
        com.nimbusds.jose.jwk.source.ImmutableSecret<com.nimbusds.jose.proc.SecurityContext> secret =
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(new javax.crypto.spec.SecretKeySpec(
                        TestTokens.SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        org.springframework.security.oauth2.jwt.JwtClaimsSet.Builder claims =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600));
        if (sub != null) {
            claims.subject(sub);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(secret)
                .encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader
                                .with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                        claims.build()))
                .getTokenValue();
    }
}
