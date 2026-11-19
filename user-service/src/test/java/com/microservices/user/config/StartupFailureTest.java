package com.microservices.user.config;

import com.microservices.user.UserServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starts the whole application with different profiles to prove the startup check really stops it.
 * Each run uses its own in-memory database and a random port.
 */
class StartupFailureTest {

    private static final String REAL_SECRET = "a-real-random-secret-with-more-than-32-bytes-in-it";

    private static ConfigurableApplicationContext start(String profile, String... settings) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profile,
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:startup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "--spring.main.banner-mode=off"));
        args.addAll(List.of(settings));
        return SpringApplication.run(UserServiceApplication.class, args.toArray(new String[0]));
    }

    @Test
    void prodProfile_doesNotStart_whenTheSecretIsEmpty() {
        assertThatThrownBy(() -> start("prod",
                "--app.security.jwt.secret=",
                "--app.security.admin.email=boss@company.test",
                "--app.security.admin.password=a-strong-admin-password"))
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    void prodProfile_doesNotStart_whenTheSecretIsThePlaceholder() {
        assertThatThrownBy(() -> start("prod",
                "--app.security.jwt.secret=" + StartupSecurityCheck.DEV_PLACEHOLDER_SECRET,
                "--app.security.admin.email=boss@company.test",
                "--app.security.admin.password=a-strong-admin-password"))
                .hasStackTraceContaining("JWT_SECRET")
                .hasStackTraceContaining("placeholder");
    }

    @Test
    void prodProfile_doesNotStart_whenTheAdminAccountIsNotSetUp() {
        assertThatThrownBy(() -> start("prod",
                "--app.security.jwt.secret=" + REAL_SECRET,
                "--app.security.admin.email=",
                "--app.security.admin.password="))
                .hasStackTraceContaining("ADMIN_EMAIL");
    }

    @Test
    void prodProfile_starts_withARealSecretAndAdminAccount() {
        try (ConfigurableApplicationContext context = start("prod",
                "--app.security.jwt.secret=" + REAL_SECRET,
                "--app.security.admin.email=boss@company.test",
                "--app.security.admin.password=a-strong-admin-password")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
        }
    }

    @Test
    void devProfile_starts_withThePlaceholdersFromTheConfiguration() {
        try (ConfigurableApplicationContext context = start("dev")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getProperty("app.security.jwt.secret"))
                    .isEqualTo(StartupSecurityCheck.DEV_PLACEHOLDER_SECRET);
        }
    }
}
