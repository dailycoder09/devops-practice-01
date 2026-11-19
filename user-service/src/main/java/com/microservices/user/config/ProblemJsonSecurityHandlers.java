package com.microservices.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.logging.LogSanitizer;
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
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes the 401 and 403 answers of the security layer as problem JSON:
 * {"type":"about:blank","title":"Unauthorized","status":401,"detail":"..."}
 * The same shape is used by product-service and inventory-service.
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

    /**
     * One warning per refused login. The token itself is never logged, only whether there was one.
     */
    private static void logRejected(HttpServletRequest request) {
        try {
            String reason = StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))
                    ? "invalid_token" : "missing_token";
            log.warn("Authentication rejected: {}", reason, kv("event", "auth_rejected"), kv("reason", reason));
        } catch (RuntimeException ignored) {
            // logging must never change the answer
        }
    }

    /**
     * One warning per request that a logged-in caller is not allowed to make.
     */
    private static void logDenied(HttpServletRequest request) {
        try {
            String method = LogSanitizer.clean(request.getMethod());
            String path = LogSanitizer.clean(request.getRequestURI());
            log.warn("Access denied for {} {}", method, path, kv("event", "access_denied"),
                    kv("httpMethod", method), kv("httpPath", path));
        } catch (RuntimeException ignored) {
            // logging must never change the answer
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
