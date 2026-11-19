package com.microservices.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.user.support.AbstractLoggingTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit events of user-service: user_registered, login_succeeded and login_failed.
 * Each is written once per action, by the logger "audit", with the fields of the contract.
 */
class AuditEventsTest extends AbstractLoggingTest {

    @Test
    void register_writesUserRegistered_once_withTheNewIdAndTheEmail() throws Exception {
        String email = uniqueEmail();

        MvcResult result = postJson("/api/users/register", email.toUpperCase(), PASSWORD)
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        JsonNode event = logs.single("user_registered");
        assertThat(event.get("logger_name").asText()).isEqualTo("audit");
        assertThat(event.get("level").asText()).isEqualTo("INFO");
        assertThat(event.get("newUserId").asLong()).isEqualTo(id);
        assertThat(event.get("email").asText()).isEqualTo(email);
        assertThat(event.get("userId").asText()).isEqualTo("anonymous");
        assertThat(event.get("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(event.get("traceId").asText()).isEqualTo(logs.singleAccessLine("/api/users/register")
                .get("traceId").asText());
    }

    @Test
    void register_withAnEmailThatIsTaken_writesNoUserRegistered() throws Exception {
        String email = uniqueEmail();
        register(email);

        postJson("/api/users/register", email, PASSWORD).andExpect(status().isConflict());

        assertThat(logs.events("user_registered")).hasSize(1);
    }

    @Test
    void register_withBadInput_writesNoUserRegistered() throws Exception {
        postJson("/api/users/register", "not-an-email", PASSWORD).andExpect(status().isBadRequest());

        assertThat(logs.events("user_registered")).isEmpty();
    }

    @Test
    void login_writesLoginSucceeded_once_withTheUserIdAndTheEmail() throws Exception {
        String email = uniqueEmail();
        long id = register(email);

        postJson("/api/users/login", email.toUpperCase(), PASSWORD).andExpect(status().isOk());

        JsonNode event = logs.single("login_succeeded");
        assertThat(event.get("logger_name").asText()).isEqualTo("audit");
        assertThat(event.get("level").asText()).isEqualTo("INFO");
        assertThat(event.get("userId").asText()).isEqualTo(String.valueOf(id));
        assertThat(event.get("email").asText()).isEqualTo(email);
        assertThat(logs.events("login_failed")).isEmpty();
    }

    @Test
    void loginWithAnUnknownEmail_writesLoginFailed_unknownEmail_atWarn() throws Exception {
        String email = uniqueEmail();

        postJson("/api/users/login", email, PASSWORD).andExpect(status().isUnauthorized());

        JsonNode event = logs.single("login_failed");
        assertThat(event.get("logger_name").asText()).isEqualTo("audit");
        assertThat(event.get("level").asText()).isEqualTo("WARN");
        assertThat(event.get("email").asText()).isEqualTo(email);
        assertThat(event.get("reason").asText()).isEqualTo("unknown_email");
        assertThat(event.get("userId").asText()).isEqualTo("anonymous");
        assertThat(logs.events("login_succeeded")).isEmpty();
    }

    @Test
    void loginWithAWrongPassword_writesLoginFailed_badPassword_atWarn() throws Exception {
        String email = uniqueEmail();
        register(email);

        postJson("/api/users/login", email, "this-is-not-the-password").andExpect(status().isUnauthorized());

        JsonNode event = logs.single("login_failed");
        assertThat(event.get("level").asText()).isEqualTo("WARN");
        assertThat(event.get("email").asText()).isEqualTo(email);
        assertThat(event.get("reason").asText()).isEqualTo("bad_password");
        assertThat(logs.allText()).doesNotContain("this-is-not-the-password");
    }

    @Test
    void theAnswerIsIdentical_forAnUnknownEmailAndAWrongPassword_evenThoughTheLogsTellThemApart() throws Exception {
        String known = uniqueEmail();
        register(known);

        MvcResult wrongPassword = postJson("/api/users/login", known, "this-is-not-the-password")
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownEmail = postJson("/api/users/login", uniqueEmail(), PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .isNotBlank().isEqualTo(unknownEmail.getResponse().getContentAsString());
        assertThat(wrongPassword.getResponse().getContentType()).isEqualTo(unknownEmail.getResponse().getContentType());
        assertThat(wrongPassword.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(unknownEmail.getResponse().getHeaderNames());
        for (String name : wrongPassword.getResponse().getHeaderNames()) {
            if (!name.equalsIgnoreCase("X-Trace-Id")) {
                assertThat(wrongPassword.getResponse().getHeaders(name))
                        .isEqualTo(unknownEmail.getResponse().getHeaders(name));
            }
        }
        assertThat(logs.events("login_failed").stream().map(event -> event.get("reason").asText()))
                .containsExactly("bad_password", "unknown_email");
    }

    @Test
    void loginSucceeded_andTheLoginAccessLine_nameTheUserWhoLoggedIn() throws Exception {
        String email = uniqueEmail();
        long id = register(email);

        postJson("/api/users/login", email, PASSWORD).andExpect(status().isOk());

        JsonNode line = logs.singleAccessLine("/api/users/login");
        assertThat(line.get("userId").asText()).isEqualTo(String.valueOf(id));
        assertThat(line.get("userRole").asText()).isEqualTo("USER");
    }
}
