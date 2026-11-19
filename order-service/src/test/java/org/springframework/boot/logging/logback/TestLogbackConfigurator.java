package org.springframework.boot.logging.logback;

import ch.qos.logback.classic.LoggerContext;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URL;

/**
 * Test helper: loads a logback-spring.xml into its OWN logger context, the way Spring Boot does at start-up
 * (with {@code <springProfile>} and {@code <springProperty>} working), so the real file of this service can be
 * tested for every profile without depending on how the shared test JVM was configured by its first Spring test.
 *
 * It lives in this package only because Spring Boot's configurator is not public.
 */
public final class TestLogbackConfigurator {

    private TestLogbackConfigurator() {
    }

    /** Returns a new, configured and started logger context. The caller must stop it. */
    public static LoggerContext configure(URL logbackSpringXml, ConfigurableEnvironment environment) {
        LoggerContext context = new LoggerContext();
        // The same MDC as the test thread uses, so the values a test puts in the MDC reach the log lines.
        context.setMDCAdapter(org.slf4j.MDC.getMDCAdapter());
        SpringBootJoranConfigurator configurator =
                new SpringBootJoranConfigurator(new LoggingInitializationContext(environment));
        configurator.setContext(context);
        try {
            configurator.doConfigure(logbackSpringXml);
        } catch (Exception e) {
            throw new IllegalStateException("The logback configuration could not be loaded", e);
        }
        return context;
    }
}
