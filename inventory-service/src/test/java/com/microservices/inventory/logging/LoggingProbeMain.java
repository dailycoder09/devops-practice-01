package com.microservices.inventory.logging;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;

/**
 * Started by {@link JsonLogOutputTest} in its own JVM, so the real logback-spring.xml and the real
 * application.yml are used exactly as in a deployed service (a profile is chosen with --spring.profiles.active).
 * It writes a fixed set of log lines and then flushes and stops logging.
 */
public final class LoggingProbeMain {

    public static final String LONG_VALUE = "x".repeat(50_000);

    @Configuration
    static class Empty {
    }

    private LoggingProbeMain() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (var context = application.run(args)) {
            writeProbeLines();
        }
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
    }

    private static void writeProbeLines() {
        Logger log = LoggerFactory.getLogger("com.microservices.inventory.probe");
        MDC.put("userId", "probe-user");
        MDC.put("userEmail", "probe@example.com");
        MDC.put("userRole", "ADMIN");
        MDC.put("clientIp", "203.0.113.9");
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        MDC.put("spanId", "0123456789abcdef");

        log.info("PROBE plain message");
        log.info("PROBE tricky message with \"quotes\", a back\\slash,\na line break, a tab\tand a {} placeholder", "value");
        log.info("PROBE long value {}", LONG_VALUE);
        log.info("PROBE access-like line {}", v("httpStatus", 200), kv("event", "http_request"), kv("durationMs", 12));
        AuditLog.event("probe_event", "PROBE audit {}", v("sku", "A\"B"), kv("delta", 3));
        log.warn("PROBE warn");
        log.debug("PROBE debug");
        log.error("PROBE failure with a stack trace", new IllegalStateException("probe failure", new RuntimeException("root cause")));
        MDC.clear();
    }
}
