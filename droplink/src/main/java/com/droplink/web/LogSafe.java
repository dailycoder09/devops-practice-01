package com.droplink.web;

import java.util.regex.Pattern;

/**
 * Makes text safe to write to the log: the link id is the secret of a link, so only its first 4 characters are kept; the
 * signed tokens of the local store are dropped; control characters (log forging) are replaced; long text is cut.
 */
final class LogSafe {

    private static final Pattern LINK_ID = Pattern.compile("(/(?:d|m|links)/)([0-9A-Za-z]{4})[0-9A-Za-z]{8}(?![0-9A-Za-z])");
    private static final Pattern LOCAL_TOKEN = Pattern.compile("(/local-store/(?:upload|download)/).*");
    private static final int MAX = 120;

    private LogSafe() {
    }

    static String path(String path) {
        if (path == null) {
            return "";
        }
        String safe = LOCAL_TOKEN.matcher(path).replaceFirst("$1<token>");
        safe = LINK_ID.matcher(safe).replaceAll("$1$2...");
        safe = safe.replaceAll("\\p{Cntrl}", "_");
        return safe.length() <= MAX ? safe : safe.substring(0, MAX) + "...";
    }
}
