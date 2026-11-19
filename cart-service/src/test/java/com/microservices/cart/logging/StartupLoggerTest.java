package com.microservices.cart.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.support.AbstractCartLoggingTest;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one "service_started" line: what it says, and that it never shows the secret.
 */
class StartupLoggerTest extends AbstractCartLoggingTest {

    @Autowired
    private StartupLogger startupLogger;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void theStartedLine_hasTheContractFields_andNoSecret() {
        startupLogger.onApplicationEvent(
                new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ofSeconds(3)));

        JsonNode line = logs.single("service_started");
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(line.get("message").asText()).contains("cart-service");
        assertThat(line.get("version").asText()).isNotBlank().isNotEqualTo("unknown");
        assertThat(line.get("profiles").toString()).contains("test");
        assertThat(line.get("port").isNumber()).isTrue();
        assertThat(line.get("jwtSecretConfigured").asBoolean()).isTrue();
        assertThat(line.get("database").asText()).containsIgnoringCase("H2");
        assertThat(line.get("downstream").get("product-service").asText()).isEqualTo(PRODUCTS.baseUrl());
        assertThat(logs.allText()).doesNotContain(TestTokens.SECRET);
    }

    @Test
    void theDownstreamUrl_hasNoUserNamePasswordPathOrQuery() {
        assertThat(StartupLogger.withoutCredentials("http://user:p4ssw0rd@product:8081/api?token=abc"))
                .isEqualTo("http://product:8081");
        assertThat(StartupLogger.withoutCredentials("https://shop.example.com/x")).isEqualTo("https://shop.example.com");
        assertThat(StartupLogger.withoutCredentials("")).isEqualTo("not set");
        assertThat(StartupLogger.withoutCredentials(null)).isEqualTo("not set");
        assertThat(StartupLogger.withoutCredentials("not a url ::")).isEqualTo("invalid url");
    }
}
