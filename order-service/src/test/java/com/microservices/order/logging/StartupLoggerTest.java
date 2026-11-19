package com.microservices.order.logging;

import com.microservices.order.support.JwtTestSupport;
import com.microservices.order.support.LogCapture;
import com.microservices.order.support.LogCapture.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One INFO line when the service is ready: event service_started, with the facts an operator needs and never a secret.
 */
class StartupLoggerTest {

    private static final String SECRET = JwtTestSupport.TEST_SECRET;

    private LogCapture logs;

    @BeforeEach
    void startCapture() {
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.application.name", "order-service");
        environment.setProperty("application.version", "9.9.9");
        environment.setProperty("local.server.port", "8085");
        environment.setProperty("app.security.jwt.secret", SECRET);
        environment.setProperty("order.cart-service.url", "http://cart-user:cart-password@cart.internal:8084/base?token=Q");
        environment.setProperty("order.product-service.url", "http://product.internal:8081");
        environment.setProperty("order.inventory-service.url", "https://alb.example.com/inventory");
        return environment;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (bean != null) {
            factory.addBean("bean", bean);
        }
        return factory.getBeanProvider(type);
    }

    private static DataSource dataSource(String product) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(product);
        return dataSource;
    }

    private static ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], mock(ConfigurableApplicationContext.class),
                Duration.ofSeconds(3));
    }

    @Test
    void logsOneInfoLine_withTheFactsOfTheContract() throws Exception {
        StartupLogger logger = new StartupLogger(environment(), provider(DataSource.class, dataSource("H2")),
                provider(BuildProperties.class, null));

        logger.onApplicationEvent(readyEvent());

        Line line = logs.single("service_started");
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.message()).contains("order-service");
        assertThat(line.field("version")).isEqualTo("9.9.9");
        assertThat(line.field("profiles")).isEqualTo(List.of("prod"));
        assertThat(line.field("port")).isEqualTo(8085);
        assertThat(line.field("jwtSecretConfigured")).isEqualTo(true);
        assertThat(line.field("database")).isEqualTo("H2");
        assertThat(line.field("cartServiceUrl")).isEqualTo("http://cart.internal:8084/base");
        assertThat(line.field("productServiceUrl")).isEqualTo("http://product.internal:8081");
        assertThat(line.field("inventoryServiceUrl")).isEqualTo("https://alb.example.com/inventory");
    }

    @Test
    void theBuildVersion_isUsedWhenTheBuildInfoIsThere() throws Exception {
        Properties build = new Properties();
        build.setProperty("version", "1.2.3");
        StartupLogger logger = new StartupLogger(environment(), provider(DataSource.class, dataSource("H2")),
                provider(BuildProperties.class, new BuildProperties(build)));

        logger.onApplicationEvent(readyEvent());

        assertThat(logs.single("service_started").field("version")).isEqualTo("1.2.3");
    }

    @Test
    void theSecretCredentialsAndQueriesOfTheUrls_areNeverLogged() throws Exception {
        StartupLogger logger = new StartupLogger(environment(), provider(DataSource.class, dataSource("H2")),
                provider(BuildProperties.class, null));

        logger.onApplicationEvent(readyEvent());

        assertThat(logs.allText()).doesNotContain(SECRET).doesNotContain("cart-password").doesNotContain("cart-user")
                .doesNotContain("token=").doesNotContain("Bearer");
    }

    @Test
    void aMissingSecret_isReportedAsNotConfigured_withoutAnyValue() throws Exception {
        MockEnvironment environment = environment();
        environment.setProperty("app.security.jwt.secret", "");
        StartupLogger logger = new StartupLogger(environment, provider(DataSource.class, dataSource("H2")),
                provider(BuildProperties.class, null));

        logger.onApplicationEvent(readyEvent());

        assertThat(logs.single("service_started").field("jwtSecretConfigured")).isEqualTo(false);
    }

    @Test
    void aDatabaseThatCannotBeAsked_neverStopsTheStartUp() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("database is down"));
        StartupLogger logger = new StartupLogger(environment(), provider(DataSource.class, broken),
                provider(BuildProperties.class, null));

        logger.onApplicationEvent(readyEvent());

        assertThat(logs.single("service_started").field("database")).isEqualTo("unknown");
    }

    @Test
    void withoutADataSource_theLineIsStillWritten() {
        StartupLogger logger = new StartupLogger(environment(), provider(DataSource.class, null),
                provider(BuildProperties.class, null));

        logger.onApplicationEvent(readyEvent());

        assertThat(logs.single("service_started").field("database")).isEqualTo("unknown");
    }

    @Test
    void aBadEnvironment_neverBreaksTheStartUp() {
        MockEnvironment environment = environment();
        environment.setProperty("local.server.port", "not-a-number");
        environment.setProperty("order.cart-service.url", "::: not a url :::");
        StartupLogger logger = new StartupLogger(environment, provider(DataSource.class, null),
                provider(BuildProperties.class, null));

        logger.onApplicationEvent(readyEvent());

        assertThat(logs.events("service_started")).hasSize(1);
    }
}
