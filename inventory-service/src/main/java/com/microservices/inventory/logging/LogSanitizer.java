package com.microservices.inventory.logging;

/**
 * Cleans a value that came from a caller (an SKU, a path, an email, a header) before it goes into a log message,
 * so that it cannot break the line or fake another one.
 *
 * Every control character (including CR and LF) becomes an underscore, so does the Unicode line and paragraph
 * separator, and at most 200 characters are kept. A null becomes an empty text.
 */
public final class LogSanitizer {

    /** Most characters of one caller value that go into a log message */
    public static final int MAX_LENGTH = 200;

    private LogSanitizer() {
    }

    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), MAX_LENGTH);
        // do not cut a surrogate pair in half
        if (length < value.length() && length > 0 && Character.isHighSurrogate(value.charAt(length - 1))) {
            length--;
        }
        StringBuilder cleaned = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            cleaned.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? '_' : c);
        }
        return cleaned.toString();
    }
}
