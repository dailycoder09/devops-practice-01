package com.microservices.order.logging;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Finds the address of the client for the logs: the first usable element of the X-Forwarded-For header (a load
 * balancer or gateway puts the real client there), otherwise the address of the socket.
 *
 * A header is only used if an element is at most 45 characters (the longest IPv6 text) and holds nothing but hex
 * digits, colons and dots, so a hostile value can never put text, markup or a line break into a log.
 * The header can still be faked by a client that talks to the service directly, so the result is for observability
 * only and must never be used for a security decision.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final int MAX_ADDRESS_LENGTH = 45;
    /** Reading more than this of a header would only waste time: the first elements come first. */
    private static final int MAX_HEADER_CHARACTERS = 1024;
    private static final Pattern ADDRESS = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null) {
            if (forwarded.length() > MAX_HEADER_CHARACTERS) {
                forwarded = forwarded.substring(0, MAX_HEADER_CHARACTERS);
            }
            for (String element : forwarded.split(",")) {
                String candidate = element.trim();
                if (!candidate.isEmpty() && candidate.length() <= MAX_ADDRESS_LENGTH
                        && ADDRESS.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : LogSanitizer.clean(remote);
    }
}
