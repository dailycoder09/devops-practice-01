package com.microservices.inventory.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role rules for the SERVICE token, against the real application:
 *  - reserve and release need role SERVICE (no token 401, USER 403, ADMIN 403, SERVICE 200);
 *  - a SERVICE token is refused (403) on every other write and on the ADMIN-only actuator endpoints;
 *  - GET stays public.
 */
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
        "spring.datasource.url=jdbc:h2:mem:service-role-rules-test;DB_CLOSE_DELAY=-1",
        "server-info.cloud-metadata.enabled=false",
        // Nothing listens here, so these tests can never call a real product-service.
        "inventory.product-service.url=http://localhost:1"
})
@AutoConfigureMockMvc
class ServiceRoleRulesTest {

    private static final String SKU = "ROLE-TEST-SKU";
    private static final String RESERVE = "/api/inventory/sku/" + SKU + "/reserve?quantity=1&orderId=7";
    private static final String RELEASE = "/api/inventory/sku/" + SKU + "/release?orderId=7";
    private static final String INVENTORY_JSON = "{\"skuCode\":\"SEC-TEST-1\",\"quantityOnHand\":5}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // ------------------------------------------------------------------ helpers

    private void ensureSkuWithStock() {
        jdbc.update("DELETE FROM stock_reservations WHERE sku_code = ?", SKU);
        jdbc.update("DELETE FROM inventory_items WHERE sku_code = ?", SKU);
        jdbc.update("INSERT INTO inventory_items (sku_code, quantity_on_hand) VALUES (?, 50)", SKU);
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

    /** The two stock endpoints for services. */
    static Stream<Arguments> serviceEndpoints() {
        return Stream.of(Arguments.of(RESERVE), Arguments.of(RELEASE));
    }

    /** The write endpoints that are written down here by hand (the discovered list is tested below). */
    static Stream<Arguments> adminOnlyWrites() {
        return Stream.of(
                Arguments.of("POST", "/api/inventory", INVENTORY_JSON),
                Arguments.of("PUT", "/api/inventory/999999", INVENTORY_JSON),
                Arguments.of("PATCH", "/api/inventory/sku/NO-SUCH-SKU/adjust?delta=1", null),
                Arguments.of("DELETE", "/api/inventory/999999", null),
                Arguments.of("POST", "/api/no-such-path", "{}"),
                // Look-alikes of reserve and release that must NOT get the SERVICE rule
                Arguments.of("POST", "/api/inventory/reserve?quantity=1&orderId=7", null),
                Arguments.of("POST", "/api/inventory/sku/" + SKU + "/reserve/extra?quantity=1&orderId=7", null),
                Arguments.of("POST", "/api/inventory/sku/a/b/reserve?quantity=1&orderId=7", null),
                Arguments.of("POST", "/api/inventory/sku/a/b/release?orderId=7", null),
                Arguments.of("POST", "/api/inventory/sku/" + SKU + "/release/", null),
                // Other methods on the two stock paths are ordinary writes: ADMIN only
                Arguments.of("PUT", RESERVE, null),
                Arguments.of("PATCH", RESERVE, null),
                Arguments.of("DELETE", RESERVE, null),
                Arguments.of("PUT", RELEASE, null),
                Arguments.of("PATCH", RELEASE, null),
                Arguments.of("DELETE", RELEASE, null));
    }

    // ------------------------------------------------------------------ reserve and release: the four token kinds

    @ParameterizedTest
    @MethodSource("serviceEndpoints")
    void stockEndpoint_withoutToken_returns401(String path) throws Exception {
        expectProblem(send("POST", path, null, null), 401, "Unauthorized");
    }

    @ParameterizedTest
    @MethodSource("serviceEndpoints")
    void stockEndpoint_withUserToken_returns403(String path) throws Exception {
        expectProblem(send("POST", path, null, JwtTestSupport.bearer("USER")), 403, "Forbidden");
    }

    @ParameterizedTest
    @MethodSource("serviceEndpoints")
    void stockEndpoint_withAdminToken_returns403(String path) throws Exception {
        expectProblem(send("POST", path, null, JwtTestSupport.bearer("ADMIN")), 403, "Forbidden");
    }

    @ParameterizedTest
    @MethodSource("serviceEndpoints")
    void stockEndpoint_withServiceToken_returns200(String path) throws Exception {
        ensureSkuWithStock();

        send("POST", path, null, JwtTestSupport.serviceBearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuCode").value(SKU));
    }

    @ParameterizedTest
    @MethodSource("serviceEndpoints")
    void stockEndpoint_aRefusedCaller_changesNothing(String path) throws Exception {
        ensureSkuWithStock();

        send("POST", path, null, JwtTestSupport.bearer("ADMIN")).andExpect(status().isForbidden());
        send("POST", path, null, JwtTestSupport.bearer("USER")).andExpect(status().isForbidden());
        send("POST", path, null, null).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM inventory_items WHERE sku_code = ?",
                Integer.class, SKU)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ?",
                Integer.class, SKU)).isZero();
    }

    @Test
    void stockEndpoint_refusesAnAdminOrUserWithAnAccurateSentence() throws Exception {
        send("POST", RESERVE, null, JwtTestSupport.bearer("ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("order service")));
        send("POST", RELEASE, null, JwtTestSupport.bearer("USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("order service")));
    }

    static Stream<Arguments> badServiceTokens() {
        return Stream.of(
                Arguments.of("expired SERVICE token", "Bearer " + JwtTestSupport.expiredServiceToken()),
                Arguments.of("SERVICE token signed with another secret",
                        "Bearer " + JwtTestSupport.serviceTokenSignedWithOtherSecret()),
                Arguments.of("tampered token (USER changed to SERVICE)", "Bearer " + JwtTestSupport.tamperedToService()),
                Arguments.of("text that is not a token", "Bearer not-a-jwt"),
                Arguments.of("another scheme", "Basic dXNlcjpwYXNzd29yZA=="));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badServiceTokens")
    void stockEndpoint_withBadToken_returns401(String description, String authorization) throws Exception {
        expectProblem(send("POST", RESERVE, null, authorization), 401, "Unauthorized");
        expectProblem(send("POST", RELEASE, null, authorization), 401, "Unauthorized");
    }

    @Test
    void stockEndpoint_withATokenWithoutRole_returns403() throws Exception {
        expectProblem(send("POST", RESERVE, null, "Bearer " + JwtTestSupport.tokenWithoutRole()), 403, "Forbidden");
    }

    @Test
    void stockEndpoint_roleNamesAreExact_lowerCaseServiceIsRefused() throws Exception {
        expectProblem(send("POST", RESERVE, null, JwtTestSupport.bearer("service")), 403, "Forbidden");
    }

    @Test
    void stockEndpoint_aServiceTokenPastTheSecurityLayer_keepsTheControllerAnswers() throws Exception {
        // 400 and 404 must come from the controller (not become 401 or 403)
        send("POST", "/api/inventory/sku/NO-SUCH-SKU/reserve?quantity=1&orderId=7", null, JwtTestSupport.serviceBearer())
                .andExpect(status().isNotFound());
        send("POST", "/api/inventory/sku/NO-SUCH-SKU/reserve?quantity=0&orderId=7", null, JwtTestSupport.serviceBearer())
                .andExpect(status().isBadRequest());
        send("POST", "/api/inventory/sku/NO-SUCH-SKU/release?orderId=7", null, JwtTestSupport.serviceBearer())
                .andExpect(status().isNotFound());
        send("POST", "/api/inventory/sku/NO-SUCH-SKU/release?orderId=0", null, JwtTestSupport.serviceBearer())
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ SERVICE is refused on every other write

    @ParameterizedTest
    @MethodSource("adminOnlyWrites")
    void otherWrite_withServiceToken_returns403(String method, String path, String body) throws Exception {
        expectProblem(send(method, path, body, JwtTestSupport.serviceBearer()), 403, "Forbidden");
    }

    @ParameterizedTest
    @MethodSource("adminOnlyWrites")
    void otherWrite_withoutToken_returns401(String method, String path, String body) throws Exception {
        expectProblem(send(method, path, body, null), 401, "Unauthorized");
    }

    @ParameterizedTest
    @MethodSource("adminOnlyWrites")
    void otherWrite_withUserToken_returns403(String method, String path, String body) throws Exception {
        expectProblem(send(method, path, body, JwtTestSupport.bearer("USER")), 403, "Forbidden");
    }

    @ParameterizedTest
    @MethodSource("adminOnlyWrites")
    void otherWrite_withAdminToken_passesTheSecurityLayer(String method, String path, String body) throws Exception {
        assertPassesSecurityLayer(send(method, path, body, JwtTestSupport.bearer("ADMIN")));
    }

    @Test
    void everyWriteEndpointOfTheApplication_exceptReserveAndRelease_refusesAServiceToken() throws Exception {
        // The list comes from the controllers themselves, so a write endpoint added later is checked too.
        List<String> found = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (RequestMethod method : methods) {
                if (method == RequestMethod.GET || method == RequestMethod.HEAD || method == RequestMethod.OPTIONS) {
                    continue;
                }
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    String path = pattern.replaceAll("\\{[^}]*}", "1");
                    boolean isServiceEndpoint = method == RequestMethod.POST
                            && (pattern.endsWith("/reserve") || pattern.endsWith("/release"));
                    if (isServiceEndpoint) {
                        continue;
                    }
                    found.add(method + " " + path);
                    ResultActions result = send(method.name(), path, "{}", JwtTestSupport.serviceBearer());
                    assertThat(result.andReturn().getResponse().getStatus())
                            .as("SERVICE token on " + method + " " + path)
                            .isEqualTo(403);
                }
            }
        }
        // The five endpoints of this service that existed before reserve and release
        assertThat(found).contains("POST /api/inventory", "PUT /api/inventory/1",
                "PATCH /api/inventory/sku/1/adjust", "DELETE /api/inventory/1");
    }

    @Test
    void theDiscoveredServiceEndpoints_areExactlyReserveAndRelease() {
        List<String> serviceEndpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (pattern.endsWith("/reserve") || pattern.endsWith("/release")) {
                    serviceEndpoints.add(info.getMethodsCondition().getMethods() + " " + pattern);
                }
            }
        }
        assertThat(serviceEndpoints).containsExactlyInAnyOrder(
                "[POST] /api/inventory/sku/{skuCode}/reserve",
                "[POST] /api/inventory/sku/{skuCode}/release");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/server.port", "/actuator/loggers", "/actuator/loggers/ROOT"})
    void actuator_envAndLoggers_withServiceToken_return403(String path) throws Exception {
        expectProblem(send("GET", path, null, JwtTestSupport.serviceBearer()), 403, "Forbidden");
    }

    @Test
    void actuator_changingALogLevel_withServiceToken_returns403() throws Exception {
        expectProblem(send("POST", "/actuator/loggers/ROOT", "{\"configuredLevel\":\"INFO\"}",
                JwtTestSupport.serviceBearer()), 403, "Forbidden");
    }

    // ------------------------------------------------------------------ GET stays public

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/inventory",
            "/api/inventory/sku/APPLE-IP15P-128",
            "/api/inventory/sku/APPLE-IP15P-128/audit",
            "/api/inventory/batch?skuCodes=APPLE-IP15P-128",
            "/actuator/health",
            "/api-docs"
    })
    void get_isStillPublic_withoutToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void get_withAServiceToken_works() throws Exception {
        mockMvc.perform(get("/api/inventory/sku/APPLE-IP15P-128")
                        .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()))
                .andExpect(status().isOk());
    }

    @Test
    void getOnTheStockPaths_isNotAWriteRule_andAnswersWithoutLoginDemand() throws Exception {
        // Nothing is mapped for GET on these paths; the point is that the security layer lets it through.
        int status = send("GET", RESERVE, null, null).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void a401_stillTellsTheClientToUseABearerToken() throws Exception {
        send("POST", RESERVE, null, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")));
    }
}
