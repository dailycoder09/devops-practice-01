package com.microservices.product.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;

/**
 * Writes one INFO line when the service is ready: event=service_started with the service name, version, active
 * profiles, port, whether a JWT secret is configured (true or false, never the value), the database product and
 * the base URLs of the services this one calls (without user, password or query). It never writes a secret.
 */
@Component
public class StartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final Environment environment;
    private final DataSource dataSource;

    public StartupLogger(Environment environment, @Nullable DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            String service = environment.getProperty("spring.application.name", "unknown");
            String version = environment.getProperty("application.version", "unknown");
            String profiles = profiles();
            Object port = port(event);
            boolean jwtSecretConfigured = StringUtils.hasText(environment.getProperty("app.security.jwt.secret"));

            Map<String, String> downstream = new LinkedHashMap<>();
            downstream.put("inventory-service", safeUrl(environment.getProperty("product.inventory-service.url")));

            log.info("Started {} {} on port {} (profiles: {})", v("service", service), v("version", version),
                    v("port", port), v("profiles", profiles), kv("event", "service_started"),
                    kv("jwtSecretConfigured", jwtSecretConfigured), kv("database", databaseProduct()),
                    kv("downstream", downstream));
        } catch (RuntimeException ex) {
            log.warn("Could not write the startup line ({})", ex.getClass().getName());
        }
    }

    private String profiles() {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 ? "default" : String.join(",", Arrays.asList(active));
    }

    private Object port(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof ServletWebServerApplicationContext web && web.getWebServer() != null) {
            return web.getWebServer().getPort();
        }
        return environment.getProperty("local.server.port", environment.getProperty("server.port", "unknown"));
    }

    private String databaseProduct() {
        if (dataSource == null) {
            return "none";
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception ex) {
            return "unavailable";
        }
    }

    /** The URL without user info, query and fragment. */
    static String safeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "not configured";
        }
        try {
            URI uri = new URI(url.trim());
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException | RuntimeException ex) {
            return "invalid";
        }
    }
}
