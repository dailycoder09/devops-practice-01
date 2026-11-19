package com.microservices.user.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the logging tests that call the real application through MockMvc, with tracing switched on
 * (tests switch it off by default) and the test-only failing endpoint. All subclasses use the same settings,
 * so Spring starts the application only once for them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Import(BoomController.class)
public abstract class AbstractLoggingTest {

    protected static final String PASSWORD = "correct-horse-battery";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Value("${app.security.jwt.secret}")
    protected String jwtSecret;

    protected LogCapture logs;

    @BeforeEach
    void startCapture() {
        MDC.clear();
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
        MDC.clear();
    }

    protected static String uniqueEmail() {
        return "log-" + UUID.randomUUID() + "@example.com";
    }

    protected ResultActions postJson(String path, String email, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Registers a user and returns the id. */
    protected long register(String email) throws Exception {
        return body(postJson("/api/users/register", email, PASSWORD).andExpect(status().isCreated()))
                .get("id").asLong();
    }

    /** Logs in and returns the token. */
    protected String login(String email) throws Exception {
        return body(postJson("/api/users/login", email, PASSWORD).andExpect(status().isOk())).get("token").asText();
    }

    protected String adminToken() throws Exception {
        return body(postJson("/api/users/login", "test.admin@example.com", "test-admin-password")
                .andExpect(status().isOk())).get("token").asText();
    }

    protected ResultActions getWithToken(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
