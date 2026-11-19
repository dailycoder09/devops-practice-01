package com.microservices.inventory.logging;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Finds the address of the caller for the logs.
 *
 * The result is the first element of the X-Forwarded-For header that is at most 45 characters long and made only
 * of hex digits, colons and dots; when there is none, it is the address of the socket.
 *
 * The header can be faked by any caller, so this address is for logs only. Never use it to decide who may do what.
 */
public final class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_ADDRESS_LENGTH = 45; // the longest IPv6 text form
    private static final int MAX_ELEMENTS_CHECKED = 20;
    private static final Pattern ADDRESS = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String header = request.getHeader(FORWARDED_FOR);
        if (header != null) {
            String[] elements = header.split(",", MAX_ELEMENTS_CHECKED + 1);
            for (int i = 0; i < elements.length && i < MAX_ELEMENTS_CHECKED; i++) {
                String candidate = elements[i].trim();
                if (candidate.length() <= MAX_ADDRESS_LENGTH && ADDRESS.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
