package com.microservices.user.logging;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Finds the address of the caller, for the logs only.
 *
 * The header X-Forwarded-For can be faked by anyone, so this address is for finding out what happened,
 * never for deciding who may do something.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int LONGEST_ADDRESS = 45;
    private static final Pattern ADDRESS_CHARACTERS = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIpResolver() {
    }

    /**
     * @param request the request
     * @return the first element of X-Forwarded-For that is at most 45 characters and only has the characters of an
     *         IPv4 or IPv6 address; otherwise the address of the socket
     */
    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null) {
            for (String element : forwarded.split(",")) {
                String candidate = element.trim();
                if (!candidate.isEmpty() && candidate.length() <= LONGEST_ADDRESS
                        && ADDRESS_CHARACTERS.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
