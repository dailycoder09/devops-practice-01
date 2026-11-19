package com.microservices.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security settings, read from "app.security.*" (filled from the environment variables
 * JWT_SECRET, ADMIN_EMAIL and ADMIN_PASSWORD, see application.yml).
 *
 * There is deliberately no toString(), so the secret and the admin password can never
 * end up in a log line by accident.
 *
 * @author Microservices Team
 * @version 1.0
 */
@ConfigurationProperties(prefix = "app.security")
@Getter
@Setter
public class SecurityProperties {

    private final Jwt jwt = new Jwt();
    private final Admin admin = new Admin();

    @Getter
    @Setter
    public static class Jwt {

        /**
         * The key that signs the tokens (at least 32 bytes). Property: app.security.jwt.secret
         */
        private String secret;
    }

    @Getter
    @Setter
    public static class Admin {

        /**
         * Email of the admin account created on start. Property: app.security.admin.email
         */
        private String email;

        /**
         * Password of the admin account created on start. Property: app.security.admin.password
         */
        private String password;
    }
}
