package com.microservices.user.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the rule "outside the dev profile, the service must not start with a missing
 * or placeholder secret, or without an admin account set up".
 */
class StartupSecurityCheckTest {

    private static final String REAL_SECRET = "a-real-random-secret-with-more-than-32-bytes-in-it";

    private static StartupSecurityCheck check(String profile, String secret, String adminEmail, String adminPassword) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSecret(secret);
        properties.getAdmin().setEmail(adminEmail);
        properties.getAdmin().setPassword(adminPassword);
        return new StartupSecurityCheck(environment, properties);
    }

    @Test
    void devProfile_allowsThePlaceholderSecretAndPlaceholderAdmin() {
        assertThatCode(() -> check("dev", StartupSecurityCheck.DEV_PLACEHOLDER_SECRET,
                "admin@example.com", "admin12345").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void placeholderSecret_hasTheValueFromTheContract() {
        org.assertj.core.api.Assertions.assertThat(StartupSecurityCheck.DEV_PLACEHOLDER_SECRET)
                .isEqualTo("dev-only-placeholder-secret-do-not-use-in-aws-0123456789");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void otherProfile_failsWhenTheSecretIsMissing(String missing) {
        assertThatThrownBy(() -> check("prod", missing, "boss@company.test", "a-strong-admin-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void otherProfile_failsWhenTheSecretIsStillThePlaceholder() {
        assertThatThrownBy(() -> check("prod", StartupSecurityCheck.DEV_PLACEHOLDER_SECRET,
                "boss@company.test", "a-strong-admin-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("placeholder");
    }

    @Test
    void noActiveProfile_isTreatedAsNotDev() {
        assertThatThrownBy(() -> check(null, "", "boss@company.test", "a-strong-admin-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void otherProfile_failsWhenTheAdminEmailIsMissing() {
        assertThatThrownBy(() -> check("prod", REAL_SECRET, "", "a-strong-admin-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_EMAIL");
    }

    @Test
    void otherProfile_failsWhenTheAdminPasswordIsMissing() {
        assertThatThrownBy(() -> check("prod", REAL_SECRET, "boss@company.test", null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void otherProfile_startsWithARealSecretAndAdminAccount() {
        assertThatCode(() -> check("prod", REAL_SECRET, "boss@company.test", "a-strong-admin-password").validate())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void anyProfile_failsWhenTheSecretIsShorterThan32Bytes(String profile) {
        assertThatThrownBy(() -> check(profile, "too-short-secret", "boss@company.test", "a-strong-admin-password")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void failureMessage_neverContainsTheSecretValue() {
        String shortSecret = "my-short-secret-value";

        assertThatThrownBy(() -> check("prod", shortSecret, "boss@company.test", "a-strong-admin-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(error.getMessage())
                        .doesNotContain(shortSecret));
    }
}
