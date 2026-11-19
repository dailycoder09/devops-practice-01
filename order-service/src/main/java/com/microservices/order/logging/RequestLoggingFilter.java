package com.microservices.order.logging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes ONE access line per request and puts the client address in the MDC.
 *
 * It runs after the tracing filter of Spring (so the trace id is already known) and before Spring Security (so a
 * request that is rejected with 401 or 403, or that throws, is still logged). The user fields are added to the MDC
 * later by {@link UserMdcFilter}, which sits in the security chain; this filter writes its line after the whole chain
 * has run and only then removes every MDC key that it or that filter set.
 *
 * <ul>
 *   <li>Response header X-Trace-Id carries the trace id (when there is one), also on 401, 403 and errors.</li>
 *   <li>The line: {@code HTTP GET /api/orders -> 200 in 12 ms}, with the fields event=http_request, httpMethod,
 *       httpPath (no query string), httpStatus, durationMs, and slow=true when it was slower than
 *       {@code app.logging.slow-request-ms}.</li>
 *   <li>Level: 500 and above ERROR; 401 and 403 WARN; slow WARN; health checks and metric scrapes DEBUG; the rest INFO.</li>
 *   <li>Logging never breaks a request: every step here that is only for the log catches its own failure.</li>
 * </ul>
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    static final String CLIENT_IP = "clientIp";
    static final String USER_ID = "userId";
    static final String USER_EMAIL = "userEmail";
    static final String USER_ROLE = "userRole";
    static final String ANONYMOUS = "anonymous";

    private static final String ACCESS_LINE = "HTTP {} {} -> {} in {} ms";

    private final ObjectProvider<Tracer> tracer;
    private final long slowRequestMs;

    public RequestLoggingFilter(ObjectProvider<Tracer> tracer,
                                @Value("${app.logging.slow-request-ms:2000}") long slowRequestMs) {
        this.tracer = tracer;
        this.slowRequestMs = slowRequestMs;
    }

    /** After the tracing filter (HIGHEST_PRECEDENCE + 1) and before Spring Security. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        beginRequest(request, response);
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (Throwable thrown) {
            failed = true;
            throw thrown;
        } finally {
            try {
                writeAccessLine(request, response, startNanos, failed);
            } catch (RuntimeException ignored) {
                // The access line is only for the log: it must never turn a good answer into an error.
            } finally {
                clearMdc();
            }
        }
    }

    private void beginRequest(HttpServletRequest request, HttpServletResponse response) {
        try {
            MDC.put(CLIENT_IP, ClientIpResolver.resolve(request));
        } catch (RuntimeException ignored) {
            // No client address in the log is better than no answer.
        }
        // Until the security chain has read the token, the caller is anonymous (also for a rejected token).
        MDC.put(USER_ID, ANONYMOUS);
        try {
            String traceId = currentTraceId();
            if (traceId != null) {
                response.setHeader(TRACE_HEADER, LogSanitizer.clean(traceId));
            }
        } catch (RuntimeException ignored) {
            // A broken tracer must not break the request.
        }
    }

    private String currentTraceId() {
        Tracer current = tracer.getIfAvailable();
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null) {
                String traceId = span.context().traceId();
                if (traceId != null && !traceId.isBlank()) {
                    return traceId;
                }
            }
        }
        String fromMdc = MDC.get("traceId");
        return fromMdc == null || fromMdc.isBlank() ? null : fromMdc;
    }

    private void writeAccessLine(HttpServletRequest request, HttpServletResponse response, long startNanos,
                                 boolean failed) {
        String method = LogSanitizer.clean(request.getMethod());
        String path = LogSanitizer.clean(request.getRequestURI());
        int status = response.getStatus();
        if (failed && status < 400) {
            // The exception goes on to the container, which will answer 500.
            status = 500;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean slow = durationMs > slowRequestMs;
        Level level = levelFor(method, path, status, slow);
        if (!log.isEnabledForLevel(level)) {
            return;
        }

        List<Object> args = new ArrayList<>(10);
        args.add(method);
        args.add(path);
        args.add(status);
        args.add(durationMs);
        args.add(kv("event", "http_request"));
        args.add(kv("httpMethod", method));
        args.add(kv("httpPath", path));
        args.add(kv("httpStatus", status));
        args.add(kv("durationMs", durationMs));
        if (slow) {
            args.add(kv("slow", true));
        }
        Object[] arguments = args.toArray();
        switch (level) {
            case ERROR -> log.error(ACCESS_LINE, arguments);
            case WARN -> log.warn(ACCESS_LINE, arguments);
            case DEBUG -> log.debug(ACCESS_LINE, arguments);
            default -> log.info(ACCESS_LINE, arguments);
        }
    }

    private static Level levelFor(String method, String path, int status, boolean slow) {
        if (status >= 500) {
            return Level.ERROR;
        }
        if (status == 401 || status == 403 || slow) {
            return Level.WARN;
        }
        return isQuiet(method, path) ? Level.DEBUG : Level.INFO;
    }

    /**
     * Health checks (a load balancer asks every few seconds) and metric scrapes would flood the log at INFO.
     * Admin actions on the actuator (like changing a log level) stay INFO.
     */
    private static boolean isQuiet(String method, String path) {
        if (path.equals("/healthz") || path.startsWith("/actuator/health")) {
            return true;
        }
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        return read && (path.equals("/actuator/prometheus") || path.equals("/actuator/info")
                || path.startsWith("/actuator/metrics"));
    }

    private static void clearMdc() {
        MDC.remove(CLIENT_IP);
        MDC.remove(USER_ID);
        MDC.remove(USER_EMAIL);
        MDC.remove(USER_ROLE);
    }
}
