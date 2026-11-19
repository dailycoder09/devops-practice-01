package com.microservices.cart.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes one line when the service is ready: "event=service_started" with the version, the active profiles,
 * the port, whether a JWT secret is configured (never the secret itself), the database product and the base
 * URLs of the services this one calls (without any user name or password in them).
 *
 * The name of the service and the profile are already in every JSON line, so they are not repeated as fields.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class StartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    /** The services this one calls: the name to show, and the property that holds the base URL. */
    private static final String[][] DOWNSTREAM_SERVICES = {{"product-service", "cart.product-service.url"}};

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<DataSource> dataSource;

    public StartupLogger(Environment environment, ObjectProvider<BuildProperties> buildProperties,
                         ObjectProvider<DataSource> dataSource) {
        this.environment = environment;
        this.buildProperties = buildProperties;
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            String service = environment.getProperty("spring.application.name", "unknown-service");
            String version = version();
            String[] profiles = activeProfiles();
            int port = port(event.getApplicationContext());
            boolean secretConfigured = StringUtils.hasText(environment.getProperty("app.security.jwt.secret"));
            String database = databaseProduct();
            Map<String, String> downstream = downstreamUrls();

            Object[] fields = downstream.isEmpty()
                    ? new Object[] {kv("event", "service_started"), kv("version", version), kv("profiles", profiles),
                            kv("port", port), kv("jwtSecretConfigured", secretConfigured), kv("database", database)}
                    : new Object[] {kv("event", "service_started"), kv("version", version), kv("profiles", profiles),
                            kv("port", port), kv("jwtSecretConfigured", secretConfigured), kv("database", database),
                            kv("downstream", downstream)};
            Object[] args = new Object[4 + fields.length];
            args[0] = service;
            args[1] = version;
            args[2] = Arrays.toString(profiles);
            args[3] = port;
            System.arraycopy(fields, 0, args, 4, fields.length);
            log.info("{} started: version {}, profiles {}, port {}", args);
        } catch (RuntimeException e) {
            log.warn("The startup line could not be written: {}", e.getClass().getSimpleName());
        }
    }

    private String version() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null && StringUtils.hasText(build.getVersion())) {
            return build.getVersion();
        }
        return environment.getProperty("application.version", "unknown");
    }

    private String[] activeProfiles() {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 ? active : environment.getDefaultProfiles();
    }

    private int port(ApplicationContext context) {
        if (context instanceof WebServerApplicationContext web && web.getWebServer() != null) {
            return web.getWebServer().getPort();
        }
        return environment.getProperty("server.port", Integer.class, -1);
    }

    private String databaseProduct() {
        DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            return "none";
        }
        try (Connection connection = source.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Map<String, String> downstreamUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        for (String[] service : DOWNSTREAM_SERVICES) {
            urls.put(service[0], withoutCredentials(environment.getProperty(service[1])));
        }
        return urls;
    }

    /** Scheme, host and port only: no user name, no password, no path, no query. */
    static String withoutCredentials(String url) {
        if (!StringUtils.hasText(url)) {
            return "not set";
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return "invalid url";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "invalid url";
        }
    }
}
