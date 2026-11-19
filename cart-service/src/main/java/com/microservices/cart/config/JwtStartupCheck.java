package com.microservices.cart.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Startup check for the JWT secret.
 *
 * The service refuses to start when the secret is missing, when it is too short, or (outside the
 * "dev" profile) when it is still the placeholder that only exists for local development.
 * The secret itself is never written into a message or a log.
 *
 * It is loaded through {@link SecurityConfig}, and the JwtDecoder takes its secret from here,
 * so this check always runs before any token can be checked.
 *
 * @author Microservices Team
 * @version 1.0
 */
public class JwtStartupCheck {

    /**
     * The secret used only in the dev profile. It is public (it is in git), so it must never be used on AWS.
     */
    public static final String DEV_PLACEHOLDER = "dev-only-placeholder-secret-do-not-use-in-aws-0123456789";

    private static final int MIN_SECRET_BYTES = 32;

    private final String secret;

    public JwtStartupCheck(Environment environment, @Value("${app.security.jwt.secret:}") String secret) {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        String profiles = Arrays.toString(environment.getActiveProfiles());

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("The JWT secret is missing. Set the environment variable JWT_SECRET "
                    + "to a random value of at least " + MIN_SECRET_BYTES + " bytes (active profiles: " + profiles + ").");
        }
        if (!dev && DEV_PLACEHOLDER.equals(secret)) {
            throw new IllegalStateException("The JWT secret is still the development placeholder. "
                    + "Outside the dev profile, set the environment variable JWT_SECRET to a real random value "
                    + "(active profiles: " + profiles + ").");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("The JWT secret must be at least " + MIN_SECRET_BYTES
                    + " bytes long. Set JWT_SECRET to a longer random value.");
        }
        this.secret = secret;
    }

    /** The checked secret, used to build the JwtDecoder. */
    String secret() {
        return secret;
    }
}
