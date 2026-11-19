package com.microservices.order.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the caller into the MDC, so every log line of the request says who made it: userId (the "sub" of the token),
 * userRole (the "role" claim) and, unless switched off with {@code app.logging.include-email=false}, userEmail.
 * Without a valid token the user is "anonymous". A SERVICE token shows as userId=order-service, userRole=SERVICE.
 *
 * It is added to the security chain right after {@code BearerTokenAuthenticationFilter} (see SecurityConfig), where the
 * token has just been checked. It is NOT a Spring bean on purpose: a Filter bean would also be registered as a
 * servlet filter and run twice. It leaves the values in the MDC; {@link RequestLoggingFilter}, which wraps the whole
 * chain, writes the access line first and then removes them.
 *
 * All values come from the token, so they are cleaned (no control characters, at most 200 characters) before they are
 * stored. A failure while reading the token never stops the request.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class UserMdcFilter extends OncePerRequestFilter {

    private final boolean includeEmail;

    public UserMdcFilter(boolean includeEmail) {
        this.includeEmail = includeEmail;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            putUser();
        } catch (RuntimeException ignored) {
            // The caller is only missing from the log: the request itself goes on.
        }
        chain.doFilter(request, response);
    }

    private void putUser() {
        // Never show a value that an earlier request left behind on this thread.
        MDC.remove(RequestLoggingFilter.USER_EMAIL);
        MDC.remove(RequestLoggingFilter.USER_ROLE);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            MDC.put(RequestLoggingFilter.USER_ID, RequestLoggingFilter.ANONYMOUS);
            return;
        }
        Jwt jwt = jwtAuthentication.getToken();
        String subject = LogSanitizer.clean(jwt.getSubject());
        MDC.put(RequestLoggingFilter.USER_ID, subject.isEmpty() ? "unknown" : subject);

        String role = LogSanitizer.clean(jwt.getClaimAsString("role"));
        if (!role.isEmpty()) {
            MDC.put(RequestLoggingFilter.USER_ROLE, role);
        }
        if (includeEmail && jwt.getClaim("email") instanceof String email && !email.isEmpty()) {
            MDC.put(RequestLoggingFilter.USER_EMAIL, LogSanitizer.clean(email));
        }
    }
}
