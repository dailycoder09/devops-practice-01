package com.microservices.order.logging;

/**
 * Cleans a value that came from outside (a caller, a token, another service) before it goes into a log message or a
 * log field: every control character, including CR and LF, becomes an underscore (so a value can never start a new
 * line or look like a second log entry), and at most 200 characters are kept.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class LogSanitizer {

    /** The longest value that is logged. */
    static final int MAX_LENGTH = 200;

    private LogSanitizer() {
    }

    /**
     * @param value any text, or null
     * @return the cleaned text; an empty string for null
     */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), MAX_LENGTH);
        // Do not cut a character that is made of two chars (a surrogate pair) in half.
        if (length < value.length() && length > 0 && Character.isHighSurrogate(value.charAt(length - 1))) {
            length--;
        }
        StringBuilder cleaned = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            cleaned.append(isLineOrControlCharacter(c) ? '_' : c);
        }
        return cleaned.toString();
    }

    private static boolean isLineOrControlCharacter(char c) {
        // ISO control characters are 0-31 and 127-159. U+2028 and U+2029 are line and paragraph separators.
        return Character.isISOControl(c) || c == ' ' || c == ' ';
    }
}
