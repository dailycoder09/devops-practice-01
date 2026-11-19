package com.microservices.cart.logging;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the logging, read from "app.logging.*" (see application.yml).
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@ConfigurationProperties(prefix = "app.logging")
@Getter
@Setter
public class LoggingProperties {

    /**
     * Whether the email of the caller is written to the logs (the "userEmail" field and the "email" field of
     * audit events). An email is personal data, so it can be switched off. Property: app.logging.include-email
     */
    private boolean includeEmail = true;

    /**
     * A request that takes longer than this many milliseconds is logged as a warning with slow=true.
     * Property: app.logging.slow-request-ms
     */
    private long slowRequestMs = 2000;
}
