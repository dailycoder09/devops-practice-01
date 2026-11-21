package com.droplink.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Gives every request a short id that appears on each log line of that request and in the X-Request-Id answer header,
 * so one visitor's steps can be followed in the log. It is made here, never taken from the caller (no log forging).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String KEY = "rid";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        StringBuilder id = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        MDC.put(KEY, id.toString());
        response.setHeader("X-Request-Id", id.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(KEY);
        }
    }
}
