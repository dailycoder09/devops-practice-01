package com.microservices.user.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the dev profile (the default for local runs) nothing has to be set up:
 * the placeholder secret signs the tokens and the dev admin account exists.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:dev-defaults;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevProfileDefaultsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void devAdmin_canLogIn_andTheTokenIsSignedWithThePlaceholderSecret() throws Exception {
        String response = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.get("role").asText()).isEqualTo("ADMIN");

        SignedJWT jwt = SignedJWT.parse(body.get("token").asText());
        assertThat(jwt.verify(new MACVerifier(StartupSecurityCheck.DEV_PLACEHOLDER_SECRET))).isTrue();
    }
}
