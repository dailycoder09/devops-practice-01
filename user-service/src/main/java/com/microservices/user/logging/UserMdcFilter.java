package com.microservices.user.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the caller from the token into the MDC (userId, userRole, userEmail), so every log line of the request
 * says who made it. Without a valid login the caller is "anonymous". The token of another service shows its
 * own name (for example userId=order-service, userRole=SERVICE).
 *
 * It is added to the security chain by SecurityConfig, right after the filter that reads the token. It is NOT a
 * Spring bean on purpose: a bean of type Filter would also be added to the servlet container for every URL.
 * The keys are removed again by {@link RequestLoggingFilter} when the request ends.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class UserMdcFilter extends OncePerRequestFilter {

    private final LoggingProperties properties;

    public UserMdcFilter(LoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
                Jwt jwt = jwtAuthentication.getToken();
                putUser(jwt.getSubject(), jwt.getClaimAsString("role"), jwt.getClaimAsString("email"),
                        properties.isIncludeEmail());
            } else {
                putAnonymous();
            }
        } catch (RuntimeException e) {
            // a token that cannot be read must not break the request: it is logged as anonymous
            putAnonymous();
        }
        chain.doFilter(request, response);
    }

    /**
     * Sets the caller in the MDC. Values come from a token (or the database), and are cleaned.
     * An empty role or email is left out.
     */
    public static void putUser(String id, String role, String email, boolean includeEmail) {
        String userId = LogSanitizer.clean(id);
        MDC.put(MdcKeys.USER_ID, userId.isEmpty() ? "unknown" : userId);
        putOrRemove(MdcKeys.USER_ROLE, role);
        putOrRemove(MdcKeys.USER_EMAIL, includeEmail ? email : null);
    }

    private static void putAnonymous() {
        MDC.put(MdcKeys.USER_ID, MdcKeys.ANONYMOUS);
        MDC.remove(MdcKeys.USER_ROLE);
        MDC.remove(MdcKeys.USER_EMAIL);
    }

    private static void putOrRemove(String key, String value) {
        if (StringUtils.hasText(value)) {
            MDC.put(key, LogSanitizer.clean(value));
        } else {
            MDC.remove(key);
        }
    }
}
