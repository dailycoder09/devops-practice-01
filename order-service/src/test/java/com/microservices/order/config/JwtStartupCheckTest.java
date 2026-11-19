package com.microservices.order.config;

import com.microservices.order.OrderServiceApplication;
import com.microservices.order.support.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service must refuse to start outside the dev profile when the JWT secret is missing, empty or
 * still the dev placeholder, and in every profile when it is shorter than 32 bytes.
 * Part one checks the rule in isolation, part two starts the real application (random port, own in-memory database).
 */
class JwtStartupCheckTest {

    // A copy on purpose: if someone changes the placeholder in the code, this test must notice.
    private static final String PLACEHOLDER = "dev-only-placeholder-secret-do-not-use-in-aws-0123456789";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtStartupCheck.class);

    private ApplicationContextRunner withProfile(String profile) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
    }

    // ------------------------------------------------------------------ the rule

    @Test
    void outsideDev_missingSecret_failsToStart_withAClearMessage() {
        withProfile("prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET")
                    .hasMessageContaining("prod");
        });
    }

    @Test
    void outsideDev_emptySecret_failsToStart() {
        withProfile("prod").withPropertyValues("app.security.jwt.secret=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("JWT_SECRET");
        });
    }

    @Test
    void outsideDev_placeholderSecret_failsToStart() {
        withProfile("prod").withPropertyValues("app.security.jwt.secret=" + PLACEHOLDER).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("placeholder");
        });
    }

    @Test
    void outsideDev_aRealSecret_starts() {
        withProfile("prod").withPropertyValues("app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void inDev_thePlaceholderIsAllowed() {
        withProfile("dev").withPropertyValues("app.security.jwt.secret=" + PLACEHOLDER)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aSecretShorterThan32Bytes_isRejected_inEveryProfile() {
        for (String profile : new String[] {"dev", "prod"}) {
            withProfile(profile).withPropertyValues("app.security.jwt.secret=too-short").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("32 bytes");
            });
        }
    }

    @Test
    void theSecretValueIsNeverPartOfTheErrorMessage() {
        withProfile("prod").withPropertyValues("app.security.jwt.secret=too-short").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageNotContaining("too-short"));
    }

    // ------------------------------------------------------------------ the real application

    private static ConfigurableApplicationContext startApplication(String... extraArgs) {
        String[] common = {
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:startup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "--spring.main.banner-mode=off"
        };
        String[] all = new String[common.length + extraArgs.length];
        System.arraycopy(common, 0, all, 0, common.length);
        System.arraycopy(extraArgs, 0, all, common.length, extraArgs.length);
        return new SpringApplicationBuilder(OrderServiceApplication.class).run(all);
    }

    @Test
    void realApplication_inProdWithoutSecret_doesNotStart() {
        assertThatThrownBy(() -> startApplication("--spring.profiles.active=prod", "--app.security.jwt.secret="))
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    void realApplication_inProdWithPlaceholder_doesNotStart() {
        assertThatThrownBy(() -> startApplication("--spring.profiles.active=prod",
                "--app.security.jwt.secret=" + PLACEHOLDER))
                .hasStackTraceContaining("placeholder");
    }

    @Test
    void realApplication_inProdWithARealSecret_starts() {
        try (ConfigurableApplicationContext context = startApplication("--spring.profiles.active=prod",
                "--app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET)) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
        }
    }

    @Test
    void realApplication_inDevWithoutAnySetting_startsWithThePlaceholder() {
        try (ConfigurableApplicationContext context = startApplication()) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("dev");
            // Without JWT_SECRET the dev profile falls back to the placeholder from application.yml.
            assertThat(context.getEnvironment().getProperty("app.security.jwt.secret"))
                    .isEqualTo(System.getenv().getOrDefault("JWT_SECRET", PLACEHOLDER));
        }
    }

    @Test
    void realApplication_usesTheContractPortAndDefaultServiceUrls() {
        try (ConfigurableApplicationContext context = startApplication()) {
            // The port is overridden to 0 above; the other values show what a normal local run uses.
            assertThat(context.getEnvironment().getProperty("order.product-service.url"))
                    .isEqualTo(System.getenv().getOrDefault("PRODUCT_SERVICE_URL", "http://localhost:8081"));
            assertThat(context.getEnvironment().getProperty("order.inventory-service.url"))
                    .isEqualTo(System.getenv().getOrDefault("INVENTORY_SERVICE_URL", "http://localhost:8082"));
            assertThat(context.getEnvironment().getProperty("order.cart-service.url"))
                    .isEqualTo(System.getenv().getOrDefault("CART_SERVICE_URL", "http://localhost:8084"));
            assertThat(context.getEnvironment().getProperty("order.clients.connect-timeout-ms")).isEqualTo("2000");
            assertThat(context.getEnvironment().getProperty("order.clients.read-timeout-ms")).isEqualTo("5000");
        }
    }
}
