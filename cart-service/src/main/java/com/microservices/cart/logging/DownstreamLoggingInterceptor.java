package com.microservices.cart.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes one line for every call to another service: "event=downstream_call" with the name of that service, the
 * method, the path (never the query), the status and the time it took.
 *
 * DEBUG for a good answer; WARN for a 4xx or 5xx answer; WARN with the name of the exception for a connection
 * problem or a time-out (the exception is then passed on, unchanged). It never writes headers (the Authorization
 * header), bodies or query strings.
 *
 * Writing the line never changes the call: a problem in the logging code is caught and the answer (or the
 * original exception) goes on as if the interceptor was not there.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class DownstreamLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DownstreamLoggingInterceptor.class);

    private final String downstreamService;

    /**
     * @param downstreamService the name of the service that is called, for example "product-service"
     */
    public DownstreamLoggingInterceptor(String downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long started = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException failure) {
            logFailure(request, started, failure);
            throw failure;
        }
        logAnswer(request, started, response);
        return response;
    }

    private void logAnswer(HttpRequest request, long started, ClientHttpResponse response) {
        try {
            int status = response.getStatusCode().value();
            String method = LogSanitizer.clean(String.valueOf(request.getMethod()));
            String path = path(request);
            long durationMs = elapsedMs(started);
            Object[] args = {downstreamService, method, path, status, durationMs,
                    kv("event", "downstream_call"), kv("downstreamService", downstreamService),
                    kv("httpMethod", method), kv("httpPath", path), kv("httpStatus", status),
                    kv("durationMs", durationMs)};
            String message = "Call to {}: {} {} -> {} in {} ms";
            if (status >= 400) {
                log.warn(message, args);
            } else {
                log.debug(message, args);
            }
        } catch (IOException | RuntimeException e) {
            // the answer is still handed on
        }
    }

    private void logFailure(HttpRequest request, long started, Exception failure) {
        try {
            String method = LogSanitizer.clean(String.valueOf(request.getMethod()));
            String path = path(request);
            long durationMs = elapsedMs(started);
            String error = failure.getClass().getSimpleName();
            log.warn("Call to {}: {} {} failed with {} after {} ms", downstreamService, method, path, error,
                    durationMs, kv("event", "downstream_call"), kv("downstreamService", downstreamService),
                    kv("httpMethod", method), kv("httpPath", path), kv("exception", error),
                    kv("durationMs", durationMs));
        } catch (RuntimeException e) {
            // the original exception is still thrown
        }
    }

    /** The path of the call, without the query. */
    private static String path(HttpRequest request) {
        URI uri = request.getURI();
        return LogSanitizer.clean(uri == null ? "" : uri.getRawPath());
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
