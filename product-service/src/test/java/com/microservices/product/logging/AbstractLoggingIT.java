package com.microservices.product.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.microservices.product.security.JwtTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Base for the logging tests that run against the real application (real filters, real security,
 * in-memory database, real tracing). All subclasses share one Spring context.
 * The fake "inventory-service" is a real local HTTP server that remembers what it receives.
 */
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
        "spring.datasource.url=jdbc:h2:mem:logging-it;DB_CLOSE_DELAY=-1",
        "server-info.cloud-metadata.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureObservability // tests switch tracing and metrics off by default; these tests need real traces
@Import(AbstractLoggingIT.ProbeController.class)
abstract class AbstractLoggingIT {

    static final FakeDownstreamServer INVENTORY = new FakeDownstreamServer();

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("product.inventory-service.url", INVENTORY::url);
    }

    @Autowired
    protected MockMvc mockMvc;

    protected LogCapture capture;

    private final Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.microservices.product");
    private Level originalLevel;

    @BeforeEach
    void startCapture() {
        // The dev profile logs this application at DEBUG, but another test's context may have set another level
        // (the log levels belong to the JVM, not to a Spring context): do not depend on the order of the test classes.
        originalLevel = applicationLogger.getLevel();
        applicationLogger.setLevel(Level.DEBUG);
        INVENTORY.clear();
        INVENTORY.respondWith(200, "{\"quantityOnHand\":7}");
        capture = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        capture.close();
        applicationLogger.setLevel(originalLevel);
    }

    /** Endpoints that exist only in these tests: one that fails, one that is slow. */
    @RestController
    static class ProbeController {

        @GetMapping("/test-probe/boom")
        String boom() {
            throw new IllegalStateException("kaboom with detail that must not reach the client");
        }

        @GetMapping("/test-probe/slow")
        String slow() throws InterruptedException {
            TimeUnit.MILLISECONDS.sleep(80);
            return "slow";
        }
    }
}
