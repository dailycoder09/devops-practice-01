package com.microservices.order.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Helpers that build tokens the way a real (or hostile) caller could, so the tests can check
 * what the service does with an expired token, a token signed with another secret, a SERVICE token and so on.
 */
public final class JwtTestSupport {

    /** The secret from application-test.yml. */
    public static final String TEST_SECRET = "test-only-secret-for-order-service-tests-0123456789-abcdefghij";

    private JwtTestSupport() {
    }

    /** A correctly built HS256 token for the given secret, with the contract's claims. */
    public static String token(String secret, String subject, String email, String role,
                               Instant issuedAt, Instant expiresAt) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuedAt(issuedAt)
                .expiresAt(expiresAt);
        if (subject != null) {
            claims.subject(subject);
        }
        if (email != null) {
            claims.claim("email", email);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /** A token that is valid for one hour from now. */
    public static String validToken(String secret, String subject, String email, String role) {
        Instant now = Instant.now();
        return token(secret, subject, email, role, now, now.plusSeconds(3600));
    }

    /** A USER token of the user with this id, signed with the test secret. */
    public static String userToken(long userId) {
        return validToken(TEST_SECRET, String.valueOf(userId), "user" + userId + "@example.com", "USER");
    }

    /** An ADMIN token of the user with this id, signed with the test secret. */
    public static String adminToken(long userId) {
        return validToken(TEST_SECRET, String.valueOf(userId), "admin" + userId + "@example.com", "ADMIN");
    }

    /** A SERVICE token like the one order-service creates for itself. */
    public static String serviceToken() {
        return validToken(TEST_SECRET, "order-service", null, "SERVICE");
    }

    /** Keeps the signature but swaps the payload for one with another role (a privilege-escalation attempt). */
    public static String withTamperedRole(String token, String newRole) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replaceAll("\"role\":\"[A-Z]+\"", "\"role\":\"" + newRole + "\"");
        if (changed.equals(payload)) {
            throw new IllegalStateException("The token has no role claim to change: " + payload);
        }
        String newPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + newPayload + "." + parts[2];
    }

    /** A token with no signature at all (header says alg none). */
    public static String unsigned(String subject, String email, String role) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        String payload = encoder.encodeToString(("{\"sub\":\"" + subject + "\",\"email\":\"" + email
                + "\",\"role\":\"" + role + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
