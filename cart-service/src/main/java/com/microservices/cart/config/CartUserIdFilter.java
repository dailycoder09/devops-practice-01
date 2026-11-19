package com.microservices.cart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the user id from the "sub" claim of the token, for every cart request.
 *
 * It runs after the role check, so a SERVICE token has already been refused with 403 and never gets here.
 * A "sub" that is not a whole number (or is missing) gives 401, before the request body is looked at
 * and before anything is read or written. The id is handed to the controller as a request attribute.
 *
 * It is NOT a Spring bean on purpose: a bean of type Filter would also be added to the servlet container
 * for every URL. {@link SecurityConfig} puts it into the security filter chain.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class CartUserIdFilter extends OncePerRequestFilter {

    /**
     * Name of the request attribute that holds the user id (a Long)
     */
    public static final String USER_ID_ATTRIBUTE = "cart.userId";

    private final RequestMatcher cartRequests;
    private final ProblemJsonSecurityHandlers problemHandlers;

    public CartUserIdFilter(RequestMatcher cartRequests, ProblemJsonSecurityHandlers problemHandlers) {
        this.cartRequests = cartRequests;
        this.problemHandlers = problemHandlers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !cartRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            userId = parseUserId(jwtAuthentication.getToken().getSubject());
        }
        if (userId == null) {
            logger.debug("Cart request refused: the token has no usable user id in 'sub'");
            problemHandlers.commence(request, response, new BadCredentialsException("The token has no user id"));
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        chain.doFilter(request, response);
    }

    /**
     * The user id is {@code Long.parseLong(sub)}. Anything else (text, empty, too big, missing) is not a user id.
     */
    static Long parseUserId(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
