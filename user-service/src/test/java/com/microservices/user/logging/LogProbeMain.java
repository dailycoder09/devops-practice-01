package com.microservices.user.logging;

import ch.qos.logback.classic.LoggerContext;
import com.microservices.user.UserServiceApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Not a test. {@link LogOutputTest} starts this class in its own JVM, so the real logback configuration
 * writes to a real stdout and a real file. It starts the application, makes one real request, writes a few
 * awkward log lines (quotes, line breaks, a very long value, an exception) and exits.
 */
public final class LogProbeMain {

    public static final String TRICKY = "quote \" backslash \\ line one\nline two\ttab unicode \u00e9\u20ac {\"fake\":\"json\"}";
    public static final String LONG = "L".repeat(50_000);

    private LogProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext context = SpringApplication.run(UserServiceApplication.class, args);
        String port = context.getEnvironment().getProperty("local.server.port");

        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/info"))
                .header("X-Forwarded-For", "203.0.113.7").build(), HttpResponse.BodyHandlers.discarding());
        Thread.sleep(300);

        Logger log = LoggerFactory.getLogger("com.microservices.user.probe");
        MDC.put("userId", "42");
        MDC.put("userEmail", "probe@example.com");
        MDC.put("userRole", "USER");
        MDC.put("clientIp", "198.51.100.1");
        log.info(TRICKY);
        log.info(LONG);
        log.error("an error with a cause", new IllegalStateException("outer failure", new RuntimeException("inner cause")));
        AuditLog.event("probe_event", "Probe audit event", kv("sku", "A\"B\nC"));
        MDC.clear();

        context.close();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        System.exit(0);
    }
}
