package com.microservices.user.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.user.support.LogCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 9: app.logging.include-email=false removes the email from the logs.
 * The same settings also set a 1 ms limit for "slow", so a login (BCrypt) is slow and is flagged.
 */
@SpringBootTest(properties = {"app.logging.include-email=false", "app.logging.slow-request-ms=1"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class IncludeEmailSwitchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private LogCapture logs;

    @BeforeEach
    void start() {
        MDC.clear();
        logs = LogCapture.start();
    }

    @AfterEach
    void stop() {
        logs.close();
        MDC.clear();
    }

    private String json(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    @Test
    void withTheSwitchOff_noEmailIsLoggedAnywhere_butTheUserIdStays() throws Exception {
        String email = "private-" + UUID.randomUUID() + "@example.com";
        long id = objectMapper.readTree(mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON).content(json(email, "a-fine-password")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        String token = objectMapper.readTree(mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(email, "a-fine-password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("someone-else-" + UUID.randomUUID() + "@example.com", "a-fine-password")))
                .andExpect(status().isUnauthorized());

        JsonNode line = logs.singleAccessLine("/api/users/me");
        assertThat(line.get("userId").asText()).isEqualTo(String.valueOf(id));
        assertThat(line.get("userRole").asText()).isEqualTo("USER");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(logs.single("login_succeeded").has("email")).isFalse();
        assertThat(logs.single("login_succeeded").get("userId").asText()).isEqualTo(String.valueOf(id));
        assertThat(logs.single("user_registered").has("email")).isFalse();
        assertThat(logs.single("login_failed").has("email")).isFalse();
        assertThat(logs.single("login_failed").get("reason").asText()).isEqualTo("unknown_email");
        assertThat(logs.allText()).doesNotContain("private-").doesNotContain("someone-else-").doesNotContain("@example.com");
    }

    @Test
    void aRequestSlowerThanTheLimit_isWarn_withSlowTrue() throws Exception {
        String email = "slow-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(email, "a-fine-password"))).andExpect(status().isCreated());

        JsonNode line = logs.singleAccessLine("/api/users/register");
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("slow").asBoolean()).isTrue();
        assertThat(line.get("httpStatus").asInt()).isEqualTo(201);
    }
}
