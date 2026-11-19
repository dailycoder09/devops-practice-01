package com.microservices.order.logging;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.Arrays;

/**
 * Audit events: the things people ask "who did that?" about (a checkout, a rejected order, ...).
 *
 * Every event goes to one logger named "audit", with a fixed event name as the structured field {@code event} and the
 * fields the caller gives (made with {@code StructuredArguments.kv}). The user, the client IP and the trace id are not
 * passed in: they come from the MDC of the request.
 *
 * Most events are INFO, but a refusal is WARN, a failure that needs a person is ERROR and the issuing of the internal
 * token is DEBUG, so there is one method per level. Logging never throws: a problem inside an audit call must never
 * change the outcome of the action that is being logged.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private AuditLog() {
    }

    /** An INFO event. */
    public static void event(String event, String message, Object... args) {
        log(Level.INFO, event, message, args);
    }

    /** A WARN event (a refusal or a handled problem). */
    public static void warn(String event, String message, Object... args) {
        log(Level.WARN, event, message, args);
    }

    /** An ERROR event (something that needs a person). */
    public static void error(String event, String message, Object... args) {
        log(Level.ERROR, event, message, args);
    }

    /** A DEBUG event (written only when the audit logger is switched to DEBUG). */
    public static void debug(String event, String message, Object... args) {
        log(Level.DEBUG, event, message, args);
    }

    private static void log(Level level, String event, String message, Object[] args) {
        try {
            if (!AUDIT.isEnabledForLevel(level)) {
                return;
            }
            // The event field goes last, so it never shifts the {} placeholders of the message.
            Object[] all = Arrays.copyOf(args, args.length + 1);
            all[args.length] = StructuredArguments.kv("event", event);
            switch (level) {
                case ERROR -> AUDIT.error(message, all);
                case WARN -> AUDIT.warn(message, all);
                case DEBUG -> AUDIT.debug(message, all);
                default -> AUDIT.info(message, all);
            }
        } catch (RuntimeException ignored) {
            // Logging must never break a request or change the result of a checkout.
        }
    }
}
