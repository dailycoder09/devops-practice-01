package com.microservices.user.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the access rules of the service: which URLs are public, which need a login,
 * which need the ADMIN role, and what the 401 and 403 answers look like.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Turns the metrics export back on (tests switch it off), so /actuator/prometheus exists here as it does when running.
@AutoConfigureObservability
@ActiveProfiles("test")
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void createTokens() throws Exception {
        String email = "rules-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "a-fine-password"))))
                .andExpect(status().isCreated());
        userToken = loginToken(email, "a-fine-password");
        adminToken = loginToken("test.admin@example.com", "test-admin-password");
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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
    void envAndLoggers_return403_forAUserToken(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/env/spring.application.name", "/actuator/loggers",
            "/actuator/loggers/ROOT"})
    void envAndLoggers_return200_forAnAdminToken(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void loggers_cannotBeChanged_withoutAdmin() throws Exception {
        String change = "{\"configuredLevel\":\"DEBUG\"}";

        mockMvc.perform(post("/actuator/loggers/ROOT").contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/actuator/loggers/ROOT").contentType(MediaType.APPLICATION_JSON).content(change)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void env_neverShowsTheSecretOrTheAdminPassword_evenToAnAdmin() throws Exception {
        String envJson = mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(envJson).doesNotContain(jwtSecret).doesNotContain("test-admin-password");
    }

    // ---------------------------------------------------------------- everything else needs a login

    @Test
    void unknownAndNonPublicUrls_return401_withoutToken() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/something-else")).andExpect(status().isUnauthorized());
        // only POST is public on register and login
        mockMvc.perform(get("/api/users/register")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUrl_with_aValidToken_isA404NotA401() throws Exception {
        mockMvc.perform(get("/api/users/something-else").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- what 401 and 403 look like

    @Test
    void unauthorizedAnswer_isProblemJson_withTheContractFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("detail").asText()).isNotBlank();
    }

    @Test
    void invalidTokenAnswer_isTheSameProblemJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
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
    void tokenWithTamperedRole_isRejectedNotTreatedAsAdmin() throws Exception {
        String tampered = JwtTestSupport.withTamperedRole(userToken, "ADMIN");

        mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- stateless, no cookies

    @Test
    void service_isStateless_noSessionAndNoCookies() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
