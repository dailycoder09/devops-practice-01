package com.microservices.inventory.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes audit events: the things people ask "who did that?" about.
 *
 * Every event goes through one logger named {@code audit}. It has a fixed {@code event} name (a top-level JSON
 * field) and the fields the caller passes (use {@code StructuredArguments.v(name, value)} for a value that is also
 * part of the message, and {@code kv(name, value)} for a field that is only in the JSON). Who did it (userId,
 * userEmail, userRole, clientIp) and the trace id come from the MDC of the request.
 *
 * Writing an event never throws: logging must not break the action it describes.
 */
public final class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private AuditLog() {
    }

    /** An audit event at INFO. */
    public static void event(String event, String message, Object... args) {
        write(false, event, message, args);
    }

    /** An audit event that is a warning (a refused or suspicious action). */
    public static void warn(String event, String message, Object... args) {
        write(true, event, message, args);
    }

    private static void write(boolean warning, String event, String message, Object[] args) {
        try {
            int given = args == null ? 0 : args.length;
            Object[] all = args == null ? new Object[1] : Arrays.copyOf(args, given + 1);
            // the event goes last: it must not take the place of a {} in the message
            all[given] = kv("event", event);
            String text = message == null ? "" : message;
            if (warning) {
                AUDIT.warn(text, all);
            } else {
                AUDIT.info(text, all);
            }
        } catch (RuntimeException ignored) {
            // never break the action that is being logged
        }
    }
}
