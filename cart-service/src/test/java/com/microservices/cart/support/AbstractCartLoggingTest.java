package com.microservices.cart.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Base for the logging tests that call the real application (tracing is on, see {@link AbstractCartApiTest}),
 * with a log capture around each test and the test-only failing endpoint. All subclasses use the same settings,
 * so Spring starts the application only once for them.
 */
@Import(BoomController.class)
public abstract class AbstractCartLoggingTest extends AbstractCartApiTest {

    protected LogCapture logs;

    @BeforeEach
    void startCapture() {
        MDC.clear();
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
        MDC.clear();
    }

    protected ResultActions getWithToken(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token)));
    }

    protected static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }
}
