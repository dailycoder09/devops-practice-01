package com.microservices.product.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;

/**
 * Writes one access-log line per request, and puts the caller's address in the MDC.
 *
 * It runs after Spring's tracing filter (so the trace id is known) and before Spring Security (so the answers
 * of the security filters, 401 and 403, are logged too). It sets the response header X-Trace-Id, runs the rest of
 * the chain, and in a finally block writes the line and removes every MDC key that it or {@link UserMdcFilter} set.
 *
 * The line: {@code HTTP GET /api/products -> 200 in 12 ms} with the fields event=http_request, httpMethod, httpPath
 * (no query string), httpStatus and durationMs. The user fields come from the MDC (set by UserMdcFilter, which runs
 * inside the security chain, before this line is written).
 *
 * Level: 500 and above ERROR; 401 and 403 WARN; a request slower than app.logging.slow-request-ms WARN with slow=true;
 * health checks DEBUG; everything else INFO.
 *
 * Nothing in here may break a request: a problem in the logging code is swallowed (the first one is reported once).
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_HEADER = "X-Trace-Id";

    private static final String[] OWN_MDC_KEYS = {"clientIp", "userId", "userEmail", "userRole"};
    private static final String ANONYMOUS = "anonymous";
    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");
    private static final AtomicBoolean PROBLEM_REPORTED = new AtomicBoolean();
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private long slowRequestMs;

    public RequestLoggingFilter(@Value("${app.logging.slow-request-ms:2000}") long slowRequestMs) {
        this.slowRequestMs = slowRequestMs;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        prepare(request, response);
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            finish(request, response, start, failure);
        }
    }

    private void prepare(HttpServletRequest request, HttpServletResponse response) {
        try {
            MDC.put("clientIp", ClientIpResolver.resolve(request));
        } catch (RuntimeException ex) {
            reportProblem(ex);
        }
        try {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                response.setHeader(TRACE_HEADER, traceId);
            }
        } catch (RuntimeException ex) {
            reportProblem(ex);
        }
    }

    private void finish(HttpServletRequest request, HttpServletResponse response, long startNanos, Throwable failure) {
        try {
            writeAccessLine(request, response, startNanos, failure);
        } catch (RuntimeException ex) {
            reportProblem(ex);
        } finally {
            for (String key : OWN_MDC_KEYS) {
                MDC.remove(key);
            }
        }
    }

    private void writeAccessLine(HttpServletRequest request, HttpServletResponse response, long startNanos,
                                 Throwable failure) {
        int status = response.getStatus();
        if (failure != null && status < 400) {
            status = 500; // the exception leaves the filter chain: the container will answer 500
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String method = LogSanitizer.clean(request.getMethod());
        String path = LogSanitizer.clean(PATH_PARAMETERS.matcher(String.valueOf(request.getRequestURI())).replaceAll(""));
        boolean health = path.startsWith("/actuator/health") || path.equals("/healthz");
        boolean slow = durationMs > slowRequestMs;

        if (health && !slow && status < 500 && status != 401 && status != 403 && !log.isDebugEnabled()) {
            return; // nothing would be written: skip the work
        }
        if (MDC.get("userId") == null) {
            MDC.put("userId", ANONYMOUS); // a request that never reached the user filter (for example a rejected token)
        }

        String format = "HTTP {} {} -> {} in {} ms";
        Object[] fields = slow
                ? new Object[] {v("httpMethod", method), v("httpPath", path), v("httpStatus", status),
                        v("durationMs", durationMs), kv("event", "http_request"), kv("slow", true)}
                : new Object[] {v("httpMethod", method), v("httpPath", path), v("httpStatus", status),
                        v("durationMs", durationMs), kv("event", "http_request")};

        if (status >= 500) {
            log.error(format, fields);
        } else if (status == 401 || status == 403 || slow) {
            log.warn(format, fields);
        } else if (health) {
            log.debug(format, fields);
        } else {
            log.info(format, fields);
        }
    }

    private static void reportProblem(RuntimeException ex) {
        if (PROBLEM_REPORTED.compareAndSet(false, true)) {
            try {
                log.warn("Request logging had a problem ({}); further problems of this kind are not reported",
                        ex.getClass().getName());
            } catch (RuntimeException ignored) {
                // logging must never break a request
            }
        }
    }
}
