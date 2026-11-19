package com.microservices.user.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Startup Security Check
 *
 * Stops the application from starting with unsafe security settings:
 * <ul>
 *   <li>Outside the dev profile the JWT secret must be set and must not be the dev placeholder.</li>
 *   <li>Outside the dev profile ADMIN_EMAIL and ADMIN_PASSWORD must be set.</li>
 *   <li>In every profile the secret must be at least 32 bytes (HS256 cannot sign with less).</li>
 * </ul>
 * The messages never contain the secret or the password.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@Slf4j
public class StartupSecurityCheck {

    /**
     * The secret used only in the dev profile. It is public (it is in git), so it must never be used on AWS.
     */
    public static final String DEV_PLACEHOLDER_SECRET =
            "dev-only-placeholder-secret-do-not-use-in-aws-0123456789";

    private static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;
    private final SecurityProperties properties;

    public StartupSecurityCheck(Environment environment, SecurityProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        String secret = properties.getJwt().getSecret();

        if (!dev) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "The JWT secret is not set. Set the environment variable JWT_SECRET to a random value of "
                                + "at least 32 bytes (for example: openssl rand -base64 48).");
            }
            if (DEV_PLACEHOLDER_SECRET.equals(secret)) {
                throw new IllegalStateException(
                        "JWT_SECRET is still the dev placeholder. The placeholder is public, so it must not be used "
                                + "outside the dev profile. Set JWT_SECRET to a random value of at least 32 bytes.");
            }
            if (isBlank(properties.getAdmin().getEmail())) {
                throw new IllegalStateException(
                        "The admin email is not set. Set the environment variable ADMIN_EMAIL.");
            }
            if (isBlank(properties.getAdmin().getPassword())) {
                throw new IllegalStateException(
                        "The admin password is not set. Set the environment variable ADMIN_PASSWORD.");
            }
        }

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short. It must be at least " + MIN_SECRET_BYTES + " bytes long.");
        }

        log.info("Security settings checked (dev profile: {})", dev);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
