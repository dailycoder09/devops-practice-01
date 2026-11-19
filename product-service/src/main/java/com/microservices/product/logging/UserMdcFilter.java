package com.microservices.product.logging;

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
 * Puts who is calling in the MDC, so every log line of the request says it.
 *
 * It sits in the security chain right after the bearer-token filter. For a verified token it sets userId (the "sub"
 * claim), userRole (the "role" claim) and, unless switched off, userEmail (the "email" claim), all cleaned. A SERVICE
 * token from another service shows as userId=order-service, userRole=SERVICE. Everyone else is userId=anonymous.
 *
 * It does not remove the keys: {@link RequestLoggingFilter} writes the access line after the chain has run and then
 * removes them. It is not a Spring bean on purpose, so that it is only in the security chain and runs once.
 * A problem in here never breaks the request.
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
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
                Jwt jwt = jwtAuthentication.getToken();
                String subject = jwt.getSubject();
                MDC.put("userId", subject == null ? "unknown" : LogSanitizer.clean(subject));
                putIfPresent("userRole", claim(jwt, "role"));
                if (includeEmail) {
                    putIfPresent("userEmail", claim(jwt, "email"));
                }
            } else {
                MDC.put("userId", "anonymous");
            }
        } catch (RuntimeException ignored) {
            // logging must never break a request
        }
        chain.doFilter(request, response);
    }

    private static String claim(Jwt jwt, String name) {
        try {
            return jwt.getClaimAsString(name);
        } catch (RuntimeException ex) {
            return null; // a claim of an unexpected type
        }
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, LogSanitizer.clean(value));
        }
    }
}
