package com.microservices.cart.logging;

/**
 * Cleans a value that came from a caller (an email, a SKU, a path, a header) before it goes into a log line,
 * so nobody can fake a second log line or break the layout of a line.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class LogSanitizer {

    /** The longest value that is kept. */
    public static final int MAX_LENGTH = 200;

    private LogSanitizer() {
    }

    /**
     * @param value any text, or null
     * @return empty for null; otherwise the text with every control character (CR and LF too) turned into "_",
     *         cut to at most 200 characters
     */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), MAX_LENGTH);
        // do not cut a character that is made of two chars (an emoji) in half
        if (length < value.length() && length > 0 && Character.isHighSurrogate(value.charAt(length - 1))) {
            length--;
        }
        StringBuilder cleaned = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            cleaned.append(isLineOrControl(c) ? '_' : c);
        }
        return cleaned.toString();
    }

    private static boolean isLineOrControl(char c) {
        // U+2028 and U+2029 are not control characters, but some log viewers show them as line breaks
        return Character.isISOControl(c) || c == ' ' || c == ' ';
    }
}
