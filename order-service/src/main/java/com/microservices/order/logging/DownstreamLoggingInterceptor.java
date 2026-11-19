package com.microservices.order.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Logs every call to another service as one line: event=downstream_call with downstreamService (the name given when
 * this interceptor is created), httpMethod, httpPath (no query string), httpStatus and durationMs.
 *
 * <ul>
 *   <li>DEBUG for a successful answer (below 400).</li>
 *   <li>WARN for 4xx and 5xx.</li>
 *   <li>WARN with the exception class name (field exception) for an I/O error or a time-out; the exception is then
 *       thrown again unchanged, without a stack trace in the log (the caller handles it).</li>
 * </ul>
 *
 * It never logs a header, a body or a query string, so no token can leak through it. A failure inside the logging
 * itself is swallowed: the call and its result are never changed.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class DownstreamLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DownstreamLoggingInterceptor.class);

    private final String downstreamService;

    public DownstreamLoggingInterceptor(String downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long startNanos = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException failure) {
            logFailure(request, startNanos, failure);
            throw failure;
        }
        logAnswer(request, response, startNanos);
        return response;
    }

    private void logAnswer(HttpRequest request, ClientHttpResponse response, long startNanos) {
        try {
            int status;
            try {
                status = response.getStatusCode().value();
            } catch (IOException | RuntimeException unreadable) {
                write(Level.WARN, request, startNanos, null, unreadable);
                return;
            }
            write(status >= 400 ? Level.WARN : Level.DEBUG, request, startNanos, status, null);
        } catch (RuntimeException ignored) {
            // Logging must never change the result of the call.
        }
    }

    private void logFailure(HttpRequest request, long startNanos, Exception failure) {
        try {
            write(Level.WARN, request, startNanos, null, failure);
        } catch (RuntimeException ignored) {
            // Logging must never hide the real failure.
        }
    }

    private void write(Level level, HttpRequest request, long startNanos, Integer status, Exception failure) {
        if (!log.isEnabledForLevel(level)) {
            return;
        }
        String method = LogSanitizer.clean(request.getMethod().name());
        String path = LogSanitizer.clean(request.getURI().getPath());
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        Object[] args;
        String message;
        if (failure == null) {
            message = "{} {} {} -> {} in {} ms";
            args = new Object[] {downstreamService, method, path, status, durationMs,
                    kv("event", "downstream_call"), kv("downstreamService", downstreamService),
                    kv("httpMethod", method), kv("httpPath", path), kv("httpStatus", status),
                    kv("durationMs", durationMs)};
        } else {
            String exception = failure.getClass().getSimpleName();
            message = "{} {} {} failed with {} in {} ms";
            args = new Object[] {downstreamService, method, path, exception, durationMs,
                    kv("event", "downstream_call"), kv("downstreamService", downstreamService),
                    kv("httpMethod", method), kv("httpPath", path), kv("exception", exception),
                    kv("durationMs", durationMs)};
        }
        switch (level) {
            case WARN -> log.warn(message, args);
            default -> log.debug(message, args);
        }
    }
}
