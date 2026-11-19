package com.microservices.cart.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Audit events: the things people ask "who did that?" about (a change of the cart).
 *
 * Every event goes through one logger named "audit" and carries a fixed "event" name and its own fields as
 * top-level JSON fields. Who did it (userId, userEmail, userRole, clientIp) and the trace id come from the MDC.
 * Pass the fields with {@code StructuredArguments.kv("name", value)}. A value that came from a caller must be
 * cleaned with {@link LogSanitizer} first. Never pass a password, a hash or a token.
 *
 * Writing an audit line never throws: a problem in the logging must not break the request.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private AuditLog() {
    }

    /**
     * An event that happened (INFO).
     *
     * @param event   the fixed name of the event, for example "cart_item_set"
     * @param message a short sentence; it may use {} for the first fields
     * @param args    the fields, made with kv("name", value)
     */
    public static void event(String event, String message, Object... args) {
        try {
            if (AUDIT.isInfoEnabled()) {
                AUDIT.info(message, withEvent(event, args));
            }
        } catch (RuntimeException ignored) {
            // logging must never break the request
        }
    }

    /**
     * An event that was refused or looks suspicious (WARN), for example a failed login.
     */
    public static void warn(String event, String message, Object... args) {
        try {
            if (AUDIT.isWarnEnabled()) {
                AUDIT.warn(message, withEvent(event, args));
            }
        } catch (RuntimeException ignored) {
            // logging must never break the request
        }
    }

    /**
     * An event that needs a human (ERROR).
     */
    public static void error(String event, String message, Object... args) {
        try {
            if (AUDIT.isErrorEnabled()) {
                AUDIT.error(message, withEvent(event, args));
            }
        } catch (RuntimeException ignored) {
            // logging must never break the request
        }
    }

    /** The event name goes last, so the {} of the message still match the first fields. */
    private static Object[] withEvent(String event, Object[] args) {
        Object[] all = new Object[(args == null ? 0 : args.length) + 1];
        if (args != null) {
            System.arraycopy(args, 0, all, 0, args.length);
        }
        all[all.length - 1] = kv("event", event);
        return all;
    }
}
