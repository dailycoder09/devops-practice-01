package com.microservices.user.logging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes one access-log line for every request, and puts the address of the caller into the MDC.
 *
 * It runs right after the tracing filter (so the trace id is known) and before Spring Security (so requests that
 * Spring Security refuses with 401 or 403, and requests that throw, are logged too). The user fields of the line
 * (userId, userEmail, userRole) are put into the MDC later, by {@link UserMdcFilter} inside the security chain.
 * Because the line is written after the chain has run, it carries them.
 *
 * The line has the method, the path (never the query string, which can hold secrets), the status and the time it
 * took. Level: 500 and above ERROR; 401 and 403 WARN; a slow request WARN (slow=true); health checks and metric
 * scrapes DEBUG when they succeed, so polling does not fill the log; everything else INFO.
 *
 * Whatever happens in here, the request itself is not changed: problems in the logging code are caught, and the
 * MDC keys are always removed at the end, so nothing leaks into the next request on the same thread.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter implements Ordered {

    /** After the tracing filter (HIGHEST_PRECEDENCE + 1), before Spring Security. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** Response header that tells the caller which trace the request belongs to. */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final LoggingProperties properties;
    private final Tracer tracer;

    @Autowired
    public RequestLoggingFilter(ObjectProvider<LoggingProperties> properties, ObjectProvider<Tracer> tracer) {
        this(properties.getIfAvailable(LoggingProperties::new), tracer.getIfAvailable());
    }

    public RequestLoggingFilter(LoggingProperties properties, Tracer tracer) {
        this.properties = properties;
        this.tracer = tracer;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        prepare(request, response);
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            try {
                writeAccessLine(request, response, started, failure);
            } finally {
                clearMdc();
            }
        }
    }

    private void prepare(HttpServletRequest request, HttpServletResponse response) {
        try {
            // a clean start, even if an earlier request left something behind on this thread
            MDC.remove(MdcKeys.USER_EMAIL);
            MDC.remove(MdcKeys.USER_ROLE);
            MDC.put(MdcKeys.USER_ID, MdcKeys.ANONYMOUS);
            MDC.put(MdcKeys.CLIENT_IP, LogSanitizer.clean(ClientIpResolver.resolve(request)));
            String traceId = currentTraceId();
            if (traceId != null) {
                response.setHeader(TRACE_ID_HEADER, traceId);
            }
        } catch (RuntimeException e) {
            warnAboutLogging("prepare", e);
        }
    }

    private String currentTraceId() {
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return StringUtils.hasText(traceId) ? traceId : null;
    }

    private void writeAccessLine(HttpServletRequest request, HttpServletResponse response, long started,
                                 Throwable failure) {
        try {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            int status = response.getStatus();
            if (failure != null && status < 400) {
                status = 500; // the container answers 500 for an exception that nobody handled
            }
            String rawPath = request.getRequestURI();
            String method = LogSanitizer.clean(request.getMethod());
            String path = LogSanitizer.clean(rawPath);
            boolean slow = durationMs > properties.getSlowRequestMs();

            Object[] values = {method, path, status, durationMs};
            Object[] fields = slow
                    ? new Object[] {kv("event", "http_request"), kv("httpMethod", method), kv("httpPath", path),
                            kv("httpStatus", status), kv("durationMs", durationMs), kv("slow", true)}
                    : new Object[] {kv("event", "http_request"), kv("httpMethod", method), kv("httpPath", path),
                            kv("httpStatus", status), kv("durationMs", durationMs)};
            Object[] args = new Object[values.length + fields.length];
            System.arraycopy(values, 0, args, 0, values.length);
            System.arraycopy(fields, 0, args, values.length, fields.length);

            String message = "HTTP {} {} -> {} in {} ms";
            if (isQuietCall(rawPath) && status < 400) {
                log.debug(message, args);
            } else if (status >= 500) {
                log.error(message, args);
            } else if (status == 401 || status == 403 || slow) {
                log.warn(message, args);
            } else {
                log.info(message, args);
            }
        } catch (RuntimeException e) {
            warnAboutLogging("access line", e);
        }
    }

    /**
     * Health checks (load balancer, container) and metric scrapes are polled all day, so a good answer is only
     * written at DEBUG. A failing one is not hidden.
     */
    private static boolean isQuietCall(String path) {
        return path != null && (path.startsWith("/actuator/health") || path.equals("/healthz")
                || path.equals("/actuator/prometheus"));
    }

    private static void clearMdc() {
        MDC.remove(MdcKeys.CLIENT_IP);
        MDC.remove(MdcKeys.USER_ID);
        MDC.remove(MdcKeys.USER_EMAIL);
        MDC.remove(MdcKeys.USER_ROLE);
    }

    private static void warnAboutLogging(String what, RuntimeException e) {
        try {
            log.warn("Request logging problem ({}): {}", what, e.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // nothing more can be done
        }
    }
}
