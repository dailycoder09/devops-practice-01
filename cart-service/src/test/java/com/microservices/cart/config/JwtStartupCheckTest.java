package com.microservices.cart.config;

import com.microservices.cart.CartServiceApplication;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service must refuse to start outside the dev profile when the JWT secret is missing, empty or
 * still the dev placeholder. Part one checks the rule in isolation, part two starts the real application.
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
    void noActiveProfile_isTreatedAsNotDev() {
        runner.withPropertyValues("app.security.jwt.secret=" + PLACEHOLDER).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("placeholder");
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
        withProfile("prod").withPropertyValues("app.security.jwt.secret=" + TestTokens.SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void inDev_thePlaceholderIsAllowed() {
        withProfile("dev").withPropertyValues("app.security.jwt.secret=" + PLACEHOLDER)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aSecretShorterThan32Bytes_isRejected_inEveryProfile() {
        withProfile("dev").withPropertyValues("app.security.jwt.secret=too-short").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("32 bytes");
        });
        withProfile("prod").withPropertyValues("app.security.jwt.secret=too-short").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("32 bytes");
        });
    }

    @Test
    void theSecretValueIsNeverPartOfTheErrorMessage() {
        withProfile("prod").withPropertyValues("app.security.jwt.secret=too-short").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageNotContaining("too-short"));
    }

    @Test
    void placeholderHasTheValueFromTheContract() {
        assertThat(JwtStartupCheck.DEV_PLACEHOLDER).isEqualTo(PLACEHOLDER);
    }

    // ------------------------------------------------------------------ the real application

    private static ConfigurableApplicationContext startApplication(String dbName, String... extraArgs) {
        String[] common = {
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1",
                "--spring.main.banner-mode=off"
        };
        String[] all = new String[common.length + extraArgs.length];
        System.arraycopy(common, 0, all, 0, common.length);
        System.arraycopy(extraArgs, 0, all, common.length, extraArgs.length);
        return new SpringApplicationBuilder(CartServiceApplication.class).run(all);
    }

    @Test
    void realApplication_inProdWithoutSecret_doesNotStart() {
        assertThatThrownBy(() -> startApplication("startup-no-secret",
                "--spring.profiles.active=prod", "--app.security.jwt.secret="))
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    void realApplication_inProdWithPlaceholder_doesNotStart() {
        assertThatThrownBy(() -> startApplication("startup-placeholder",
                "--spring.profiles.active=prod", "--app.security.jwt.secret=" + PLACEHOLDER))
                .hasStackTraceContaining("JWT_SECRET")
                .hasStackTraceContaining("placeholder");
    }

    @Test
    void realApplication_inProdWithAShortSecret_doesNotStart() {
        assertThatThrownBy(() -> startApplication("startup-short",
                "--spring.profiles.active=prod", "--app.security.jwt.secret=short"))
                .hasStackTraceContaining("32 bytes");
    }

    @Test
    void realApplication_inProdWithARealSecret_starts() {
        try (ConfigurableApplicationContext context = startApplication("startup-prod-ok",
                "--spring.profiles.active=prod", "--app.security.jwt.secret=" + TestTokens.SECRET)) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
        }
    }

    @Test
    void realApplication_inDevWithNoSettings_startsWithThePlaceholder() {
        try (ConfigurableApplicationContext context = startApplication("startup-dev-ok",
                "--spring.profiles.active=dev")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getProperty("app.security.jwt.secret")).isEqualTo(PLACEHOLDER);
        }
    }
}
