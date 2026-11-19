package com.microservices.order.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.util.Arrays;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * One INFO line when the service is ready: event=service_started with the version (from the build if it is known),
 * the active profiles, the port, whether a JWT secret is configured (never its value), the database product and the
 * base URLs of the services it calls (without credentials or query).
 *
 * It only reads settings and never fails the start-up: the line is for people, the service must start without it.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class StartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final Environment environment;
    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<BuildProperties> buildProperties;

    public StartupLogger(Environment environment, ObjectProvider<DataSource> dataSource,
                         ObjectProvider<BuildProperties> buildProperties) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            logStarted();
        } catch (RuntimeException ignored) {
            // Never stop the start-up because of a log line.
        }
    }

    private void logStarted() {
        String service = LogSanitizer.clean(environment.getProperty("spring.application.name", "order-service"));
        String version = version();
        String[] profiles = environment.getActiveProfiles();
        Integer port = port();
        boolean secretConfigured = StringUtils.hasText(environment.getProperty("app.security.jwt.secret"));

        log.info("Service {} started: version {}, profiles {}, port {}", service, version,
                Arrays.toString(profiles), port,
                kv("event", "service_started"),
                kv("version", version),
                kv("profiles", Arrays.asList(profiles)),
                kv("port", port),
                kv("jwtSecretConfigured", secretConfigured),
                kv("database", databaseProduct()),
                kv("cartServiceUrl", safeUrl(environment.getProperty("order.cart-service.url"))),
                kv("productServiceUrl", safeUrl(environment.getProperty("order.product-service.url"))),
                kv("inventoryServiceUrl", safeUrl(environment.getProperty("order.inventory-service.url"))));
    }

    private String version() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : environment.getProperty("application.version");
        return StringUtils.hasText(version) ? LogSanitizer.clean(version) : "unknown";
    }

    private Integer port() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port"));
        try {
            return port == null ? null : Integer.valueOf(port.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String databaseProduct() {
        try {
            DataSource source = dataSource.getIfAvailable();
            if (source == null) {
                return "unknown";
            }
            try (Connection connection = source.getConnection()) {
                return LogSanitizer.clean(connection.getMetaData().getDatabaseProductName());
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** scheme://host:port/path of a service URL: no user name, no password, no query, no fragment. */
    static String safeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "not set";
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return "invalid";
            }
            StringBuilder safe = new StringBuilder();
            safe.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() != -1) {
                safe.append(':').append(uri.getPort());
            }
            if (uri.getPath() != null) {
                safe.append(uri.getPath());
            }
            return LogSanitizer.clean(safe.toString());
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }
}
