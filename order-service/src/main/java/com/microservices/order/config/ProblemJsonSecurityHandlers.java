package com.microservices.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.logging.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes the 401 and 403 answers of the security layer as problem JSON:
 * {"type":"about:blank","title":"Unauthorized","status":401,"detail":"..."}
 * The same shape is used by the other services.
 *
 * Each answer is also one WARN log line: event=auth_rejected (reason missing_token or invalid_token) for a 401 and
 * event=access_denied (with method and path) for a 403. The token and the Authorization header are never logged, only
 * whether a header was sent. A failure while logging never changes the answer.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
public class ProblemJsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemJsonSecurityHandlers.class);

    private final ObjectMapper objectMapper;

    public ProblemJsonSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * No token, or a token that is invalid, expired or wrongly signed: 401
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        logRejected(request);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "A valid login token is needed. Please log in again.");
    }

    /**
     * A valid token whose role is not allowed to do this: 403
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        logDenied(request);
        write(response, HttpStatus.FORBIDDEN, "Forbidden", "Your account is not allowed to do this.");
    }

    /** A token was sent and refused (invalid_token), or none was sent (missing_token). Never the token itself. */
    private void logRejected(HttpServletRequest request) {
        try {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            String reason = authorization == null || authorization.isBlank() ? "missing_token" : "invalid_token";
            String method = LogSanitizer.clean(request.getMethod());
            String path = LogSanitizer.clean(request.getRequestURI());
            log.warn("Request rejected ({}) {} {}", reason, method, path,
                    kv("event", "auth_rejected"), kv("reason", reason),
                    kv("httpMethod", method), kv("httpPath", path));
        } catch (RuntimeException ignored) {
            // The 401 answer is the same whether or not it could be logged.
        }
    }

    private void logDenied(HttpServletRequest request) {
        try {
            String method = LogSanitizer.clean(request.getMethod());
            String path = LogSanitizer.clean(request.getRequestURI());
            log.warn("Access denied {} {}", method, path,
                    kv("event", "access_denied"), kv("httpMethod", method), kv("httpPath", path));
        } catch (RuntimeException ignored) {
            // The 403 answer is the same whether or not it could be logged.
        }
    }

    private void write(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
