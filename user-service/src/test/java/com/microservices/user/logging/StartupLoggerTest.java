package com.microservices.user.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.user.support.AbstractLoggingTest;
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
class StartupLoggerTest extends AbstractLoggingTest {

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
        assertThat(line.get("message").asText()).contains("user-service");
        assertThat(line.get("version").asText()).isNotBlank().isNotEqualTo("unknown");
        assertThat(line.get("profiles").toString()).contains("test");
        assertThat(line.get("port").isNumber()).isTrue();
        assertThat(line.get("jwtSecretConfigured").asBoolean()).isTrue();
        assertThat(line.get("database").asText()).containsIgnoringCase("H2");
        assertThat(line.has("downstream")).isFalse();
        assertThat(logs.allText()).doesNotContain(jwtSecret).doesNotContain("test-admin-password");
    }
}
