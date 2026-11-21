package com.droplink.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Safe defaults on every answer. The address of a link is a secret, so the pages never leak it through the Referer
 * header, are never cached, and are kept out of search engines. The page cannot be framed by other sites, and only
 * its own scripts run.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: https: http://localhost:* http://127.0.0.1:*; "
            + "media-src 'self' https: http://localhost:* http://127.0.0.1:*; "
            + "frame-src 'self' https: http://localhost:* http://127.0.0.1:*; "
            + "connect-src 'self' https: http://localhost:* http://127.0.0.1:*; "
            + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        String path = request.getRequestURI();
        if (!path.startsWith("/local-store/")) {
            response.setHeader("Content-Security-Policy", CSP);
        }
        if (path.startsWith("/d/") || path.startsWith("/m/") || path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Robots-Tag", "noindex, nofollow");
        }
        chain.doFilter(request, response);
    }
}
