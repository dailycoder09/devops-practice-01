package com.microservices.cart.config;

import com.microservices.cart.support.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the dev profile (the default for local runs) nothing has to be set up: the placeholder secret checks
 * the tokens, the ports and time-outs are the ones from the contract.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:dev-defaults;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevProfileDefaultsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void aTokenSignedWithThePlaceholderSecret_isAccepted() throws Exception {
        String token = JwtTestSupport.validToken(JwtStartupCheck.DEV_PLACEHOLDER, "7", "a@b.c", "USER");

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenSignedWithAnotherSecret_isRefused() throws Exception {
        String token = JwtTestSupport.validToken("another-secret-that-is-long-enough-for-hs256-0123456789", "7",
                "a@b.c", "USER");

        mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSettingsFromTheContract_areTheDefaults() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8084");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("cart-service");
        assertThat(environment.getProperty("cart.product-service.url")).isEqualTo("http://localhost:8081");
        assertThat(environment.getProperty("cart.product-service.connect-timeout-ms")).isEqualTo("2000");
        assertThat(environment.getProperty("cart.product-service.read-timeout-ms")).isEqualTo("5000");
    }
}
