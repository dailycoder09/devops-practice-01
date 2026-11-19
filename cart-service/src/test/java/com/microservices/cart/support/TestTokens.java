package com.microservices.cart.support;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tokens for the tests, signed with the test secret from application-test.yml.
 */
public final class TestTokens {

    public static final String SECRET = "test-only-secret-for-cart-service-tests-0123456789-abcdefghij";

    private static final AtomicLong NEXT_USER_ID = new AtomicLong(10_000);

    private TestTokens() {
    }

    /** A user id nobody else in this test run uses, so tests never see each other's carts. */
    public static long newUserId() {
        return NEXT_USER_ID.incrementAndGet();
    }

    public static String user(long userId) {
        return JwtTestSupport.validToken(SECRET, String.valueOf(userId), "user" + userId + "@example.com", "USER");
    }

    public static String admin(long userId) {
        return JwtTestSupport.validToken(SECRET, String.valueOf(userId), "admin" + userId + "@example.com", "ADMIN");
    }

    /** The internal token of order-service: sub order-service, role SERVICE, two minutes. */
    public static String service() {
        Instant now = Instant.now();
        return JwtTestSupport.token(SECRET, "order-service", "", "SERVICE", now, now.plusSeconds(120));
    }

    public static String withRole(String subject, String role) {
        return JwtTestSupport.validToken(SECRET, subject, "someone@example.com", role);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
