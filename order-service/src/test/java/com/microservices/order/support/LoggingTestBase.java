package com.microservices.order.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Base for the tests of logging and tracing: the whole service (as in {@link CheckoutTestBase}) with tracing turned
 * ON (tests normally switch it off), and every log event of the test captured in {@link #logs}.
 */
@AutoConfigureObservability
public abstract class LoggingTestBase extends CheckoutTestBase {

    protected LogCapture logs;

    @BeforeEach
    void startLogCapture() {
        MDC.clear();
        logs = LogCapture.start();
    }

    @AfterEach
    void stopLogCapture() {
        logs.close();
        // Nothing the request wrote may stay behind in the thread that ran it.
        assertThat(MDC.getCopyOfContextMap()).as("MDC after the test").isNullOrEmpty();
    }

    /** GET with a token (or none when the token is null). */
    protected MockHttpServletRequestBuilder getWith(String path, String token) {
        MockHttpServletRequestBuilder request = get(path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        return request;
    }

    protected static String traceIdHeader(MvcResult result) {
        return result.getResponse().getHeader("X-Trace-Id");
    }

    /** The 32-digit lower-case form of a trace id (a 64-bit id is padded with zeros, as in the W3C header). */
    protected static String normalizeTraceId(String traceId) {
        return "0".repeat(Math.max(0, 32 - traceId.length())) + traceId.toLowerCase();
    }

    /** The trace id of a W3C "traceparent" header value: 00-{trace id}-{span id}-{flags}. */
    protected static String traceIdOfTraceparent(String traceparent) {
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        return traceparent.split("-")[1];
    }
}
