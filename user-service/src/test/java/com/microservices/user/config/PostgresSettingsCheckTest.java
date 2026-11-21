package com.microservices.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The "postgres" profile must stop the service when a database setting is missing, and must never print a value. */
class PostgresSettingsCheckTest {

    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://db.example:5432/microshop")
                .withProperty("DB_USERNAME", "microshop")
                .withProperty("DB_PASSWORD", "s3cret-value-123")
                .withProperty("DB_SCHEMA", "user_svc");
    }

    @Test
    void acceptsCompleteSettings() {
        assertThatCode(() -> PostgresSettingsCheck.check(complete())).doesNotThrowAnyException();
    }

    @Test
    void namesTheMissingVariable() {
        for (String name : PostgresSettingsCheck.REQUIRED) {
            MockEnvironment without = new MockEnvironment();
            for (String other : PostgresSettingsCheck.REQUIRED) {
                if (!other.equals(name)) {
                    without.withProperty(other, complete().getProperty(other));
                }
            }
            assertThatThrownBy(() -> PostgresSettingsCheck.check(without))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name);
        }
    }

    @Test
    void rejectsBlankSchema() {
        MockEnvironment env = complete().withProperty("DB_SCHEMA", "  ");
        assertThatThrownBy(() -> PostgresSettingsCheck.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_SCHEMA");
    }

    @Test
    void rejectsAUrlThatIsNotPostgresqlWithoutShowingIt() {
        MockEnvironment env = complete().withProperty("DB_URL", "jdbc:h2:file:/data/x;AUTO_SERVER=TRUE");
        assertThatThrownBy(() -> PostgresSettingsCheck.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL")
                .hasMessageNotContaining("jdbc:h2");
    }

    @Test
    void neverShowsThePassword() {
        MockEnvironment env = complete().withProperty("DB_SCHEMA", "");
        assertThatThrownBy(() -> PostgresSettingsCheck.check(env))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cret-value-123"));
    }
}
