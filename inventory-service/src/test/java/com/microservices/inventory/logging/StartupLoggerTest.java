package com.microservices.inventory.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupLoggerTest {

    private static final String SECRET = "super-secret-jwt-value-that-must-never-be-logged-0123456789";

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.start();
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("spring.application.name", "inventory-service");
        environment.setProperty("application.version", "1.2.3");
        environment.setProperty("server.port", "8082");
        environment.setProperty("app.security.jwt.secret", SECRET);
        environment.setProperty("inventory.product-service.url", "http://user:pw@product.internal:8081/base?token=abc");
        return environment;
    }

    private static ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], new GenericApplicationContext(), Duration.ZERO);
    }

    @Test
    void onReady_writesOneServiceStartedLine_withTheAgreedFields() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData metaData = mock(java.sql.DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        new StartupLogger(environment(), dataSource).onApplicationEvent(readyEvent());

        var lines = capture.withEvent("service_started");
        assertThat(lines).hasSize(1);
        LogCapture.Line line = lines.get(0);
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.text("service")).isEqualTo("inventory-service");
        assertThat(line.text("version")).isEqualTo("1.2.3");
        assertThat(line.text("profiles")).contains("dev");
        assertThat(line.text("port")).isEqualTo("8082");
        assertThat(line.get("jwtSecretConfigured")).isEqualTo(true);
        assertThat(line.text("database")).isEqualTo("H2");
        assertThat(line.text("downstream")).contains("product-service=http://product.internal:8081/base");
    }

    @Test
    void neverWritesTheSecret_aPassword_orAQuery() {
        new StartupLogger(environment(), null).onApplicationEvent(readyEvent());

        String text = capture.allText();
        assertThat(text).doesNotContain(SECRET).doesNotContain("pw@").doesNotContain("user:").doesNotContain("token=abc");
    }

    @Test
    void aMissingSecret_isReportedAsNotConfigured_withoutFailing() {
        MockEnvironment environment = environment();
        environment.setProperty("app.security.jwt.secret", "");

        new StartupLogger(environment, null).onApplicationEvent(readyEvent());

        assertThat(capture.withEvent("service_started").get(0).get("jwtSecretConfigured")).isEqualTo(false);
    }

    @Test
    void aBrokenDatabase_doesNotStopTheStartup() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("db down with password=secret"));

        assertThatCode(() -> new StartupLogger(environment(), dataSource).onApplicationEvent(readyEvent()))
                .doesNotThrowAnyException();
        assertThat(capture.withEvent("service_started")).hasSize(1);
        assertThat(capture.withEvent("service_started").get(0).text("database")).isEqualTo("unavailable");
        assertThat(capture.allText()).doesNotContain("password=secret");
    }
}
