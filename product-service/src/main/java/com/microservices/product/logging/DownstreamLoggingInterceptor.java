package com.microservices.product.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;

/**
 * Logs every call this service makes to another service, in one line: event=downstream_call with downstreamService,
 * httpMethod, httpPath (no query), httpStatus and durationMs.
 *
 * DEBUG for a 2xx answer, WARN for 4xx and 5xx, and WARN with the exception class name for an I/O error or a
 * time-out (the exception is then thrown again, unchanged). With {@code quiet} every outcome is DEBUG: for a
 * call that is expected to fail often and quietly (the EC2 metadata lookup off AWS).
 *
 * Never logs headers, bodies or the query string. A problem in the logging code never changes the result of the call.
 */
public class DownstreamLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DownstreamLoggingInterceptor.class);
    private static final AtomicBoolean PROBLEM_REPORTED = new AtomicBoolean();

    private final String downstreamService;
    private final boolean quiet;

    public DownstreamLoggingInterceptor(String downstreamService) {
        this(downstreamService, false);
    }

    public DownstreamLoggingInterceptor(String downstreamService, boolean quiet) {
        this.downstreamService = downstreamService;
        this.quiet = quiet;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException ex) {
            logFailure(request, start, ex);
            throw ex;
        }
        logAnswer(request, response, start);
        return response;
    }

    private void logAnswer(HttpRequest request, ClientHttpResponse response, long startNanos) {
        try {
            int status = response.getStatusCode().value();
            boolean problem = status >= 400 && !quiet;
            String format = "Call to {} {} {} -> {} in {} ms";
            Object[] fields = {v("downstreamService", downstreamService), v("httpMethod", method(request)),
                    v("httpPath", path(request)), v("httpStatus", status), v("durationMs", millisSince(startNanos)),
                    kv("event", "downstream_call")};
            if (problem) {
                log.warn(format, fields);
            } else {
                log.debug(format, fields);
            }
        } catch (IOException | RuntimeException ex) {
            reportProblem(ex);
        }
    }

    private void logFailure(HttpRequest request, long startNanos, Exception failure) {
        try {
            String format = "Call to {} {} {} failed after {} ms: {}";
            Object[] fields = {v("downstreamService", downstreamService), v("httpMethod", method(request)),
                    v("httpPath", path(request)), v("durationMs", millisSince(startNanos)),
                    v("exception", failure.getClass().getName()), kv("event", "downstream_call")};
            if (quiet) {
                log.debug(format, fields);
            } else {
                log.warn(format, fields);
            }
        } catch (RuntimeException ex) {
            reportProblem(ex);
        }
    }

    private static String method(HttpRequest request) {
        return LogSanitizer.clean(request.getMethod().name());
    }

    private static String path(HttpRequest request) {
        return LogSanitizer.clean(request.getURI().getRawPath());
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void reportProblem(Exception ex) {
        if (PROBLEM_REPORTED.compareAndSet(false, true)) {
            try {
                log.warn("Downstream call logging had a problem ({}); further problems of this kind are not reported",
                        ex.getClass().getName());
            } catch (RuntimeException ignored) {
                // logging must never break a call
            }
        }
    }
}
