package com.microservices.inventory.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Test helper that builds JWTs the same way user-service does (HS256, claims sub/email/role/iat/exp).
 * The secrets here are for tests only and are never used anywhere else.
 */
public final class JwtTestSupport {

    public static final String TEST_SECRET = "test-only-secret-for-inventory-service-tests-0123456789";
    public static final String OTHER_SECRET = "a-completely-different-test-only-secret-9876543210";

    private JwtTestSupport() {
    }

    /** A valid token (8 hours) for the given role, signed with the test secret. */
    public static String token(String role) {
        Instant now = Instant.now();
        return build(role, TEST_SECRET, now, now.plus(Duration.ofHours(8)));
    }

    /** A token that expired one hour ago (well past the default clock skew of one minute). */
    public static String expiredToken(String role) {
        Instant now = Instant.now();
        return build(role, TEST_SECRET, now.minus(Duration.ofHours(9)), now.minus(Duration.ofHours(1)));
    }

    /** A token with a good shape and good claims, but signed with another secret. */
    public static String tokenSignedWithOtherSecret(String role) {
        Instant now = Instant.now();
        return build(role, OTHER_SECRET, now, now.plus(Duration.ofHours(8)));
    }

    /**
     * A valid token the way order-service makes it for stock calls: sub "order-service", role SERVICE,
     * lifetime 2 minutes, signed with the test secret.
     */
    public static String serviceToken() {
        Instant now = Instant.now();
        return build("order-service", "SERVICE", TEST_SECRET, now, now.plus(Duration.ofMinutes(2)));
    }

    /** The value of an Authorization header for a valid SERVICE token. */
    public static String serviceBearer() {
        return "Bearer " + serviceToken();
    }

    /** A SERVICE token that expired one hour ago. */
    public static String expiredServiceToken() {
        Instant now = Instant.now();
        return build("order-service", "SERVICE", TEST_SECRET, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
    }

    /** A SERVICE token signed with another secret. */
    public static String serviceTokenSignedWithOtherSecret() {
        Instant now = Instant.now();
        return build("order-service", "SERVICE", OTHER_SECRET, now, now.plus(Duration.ofMinutes(2)));
    }

    /** A USER token whose payload was changed to say SERVICE after signing (signature no longer fits). */
    public static String tamperedToService() {
        String[] parts = token("USER").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replace("\"role\":\"USER\"", "\"role\":\"SERVICE\"");
        if (changed.equals(payload)) {
            throw new IllegalStateException("Test setup error: the role claim was not found in the payload");
        }
        String newPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + newPayload + "." + parts[2];
    }

    /** A valid, correctly signed token that has no role claim at all. */
    public static String tokenWithoutRole() {
        Instant now = Instant.now();
        return build(null, TEST_SECRET, now, now.plus(Duration.ofHours(8)));
    }

    /** A USER token whose payload was changed to say ADMIN after signing (signature no longer fits). */
    public static String tamperedToAdmin() {
        String[] parts = token("USER").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replace("\"role\":\"USER\"", "\"role\":\"ADMIN\"");
        if (changed.equals(payload)) {
            throw new IllegalStateException("Test setup error: the role claim was not found in the payload");
        }
        String newPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + newPayload + "." + parts[2];
    }

    /** The value of an Authorization header for a valid token with the given role. */
    public static String bearer(String role) {
        return "Bearer " + token(role);
    }

    /** A valid token (8 hours) with the given subject, email and role, signed with the test secret. */
    public static String tokenFor(String subject, String email, String role) {
        Instant now = Instant.now();
        return build(subject, email, role, TEST_SECRET, now, now.plus(Duration.ofHours(8)));
    }

    /** The value of an Authorization header for {@link #tokenFor(String, String, String)}. */
    public static String bearerFor(String subject, String email, String role) {
        return "Bearer " + tokenFor(subject, email, role);
    }

    private static String build(String role, String secret, Instant issuedAt, Instant expiresAt) {
        return build("42", role, secret, issuedAt, expiresAt);
    }

    private static String build(String subject, String role, String secret, Instant issuedAt, Instant expiresAt) {
        return build(subject, "someone@example.com", role, secret, issuedAt, expiresAt);
    }

    private static String build(String subject, String email, String role, String secret,
                                Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email)
                .claim("role", role)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not build a test token", ex);
        }
    }
}
