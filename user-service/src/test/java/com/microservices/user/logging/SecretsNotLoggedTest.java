package com.microservices.user.logging;

import ch.qos.logback.classic.Level;
import com.microservices.user.model.User;
import com.microservices.user.support.AbstractLoggingTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 5: after a flow with register, login and token use (and failures), nothing secret is in the logs:
 * no password, no password hash, no token, no Authorization header, no JWT secret.
 */
class SecretsNotLoggedTest extends AbstractLoggingTest {

    private static final String LOGIN_PASSWORD = "an-unmistakable-Passw0rd-for-this-test";
    private static final String WRONG_PASSWORD = "wrong-Passw0rd-that-nobody-set";
    private static final String SHORT_PASSWORD = "pw-7chr";
    private static final String BROKEN_JSON_PASSWORD = "hunter2-in-broken-json";

    private void assertNothingSecret(String token, String email) {
        User stored = userRepository.findByEmail(email).orElseThrow();
        String text = logs.allText();

        assertThat(text)
                .doesNotContain(LOGIN_PASSWORD)
                .doesNotContain(WRONG_PASSWORD)
                .doesNotContain(SHORT_PASSWORD)
                .doesNotContain(BROKEN_JSON_PASSWORD)
                .doesNotContain("test-admin-password")
                .doesNotContain(stored.getPasswordHash())
                .doesNotContain(token)
                .doesNotContain(token.split("\\.")[1])
                .doesNotContain(jwtSecret)
                .doesNotContain("Bearer ");
    }

    @Test
    void atTheNormalLevels_nothingSecretIsLogged() throws Exception {
        String email = uniqueEmail();
        postJson("/api/users/register", email, LOGIN_PASSWORD).andExpect(status().isCreated());
        String token = body(postJson("/api/users/login", email, LOGIN_PASSWORD).andExpect(status().isOk()))
                .get("token").asText();
        getWithToken("/api/users/me", token).andExpect(status().isOk());
        postJson("/api/users/login", email, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        postJson("/api/users/register", uniqueEmail(), SHORT_PASSWORD).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token + "tampered"))
                .andExpect(status().isUnauthorized());

        assertNothingSecret(token, email);
        assertThat(logs.allText()).doesNotContain("Authorization");
    }

    @Test
    void evenWithDebugOn_nothingSecretIsLogged() throws Exception {
        logs.level("ROOT", Level.DEBUG).level("com.microservices", Level.DEBUG);
        String email = uniqueEmail();
        postJson("/api/users/register", email, LOGIN_PASSWORD).andExpect(status().isCreated());
        String token = body(postJson("/api/users/login", email, LOGIN_PASSWORD).andExpect(status().isOk()))
                .get("token").asText();
        getWithToken("/api/users/me", token).andExpect(status().isOk());
        postJson("/api/users/login", email, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        postJson("/api/users/register", uniqueEmail(), SHORT_PASSWORD).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"password\":" + BROKEN_JSON_PASSWORD))
                .andExpect(status().isBadRequest());
        getWithToken("/actuator/env", token).andExpect(status().isForbidden());

        assertNothingSecret(token, email);
    }
}
