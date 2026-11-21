package com.droplink.service;

import java.util.regex.Pattern;

/** Who downloads: only what the activity page needs, and never the full address. */
public record ClientInfo(String ip, String userAgent) {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]{2,45}");

    /** 203.0.113.77 becomes 203.0.113.x; an IPv6 address keeps its first three groups; anything else is "unknown". */
    public static String maskIp(String ip) {
        if (ip == null) {
            return "unknown";
        }
        String value = ip.trim();
        var v4 = IPV4.matcher(value);
        if (v4.matches()) {
            return v4.group(1) + ".x";
        }
        if (IPV6.matcher(value).matches() && value.indexOf(':') >= 0) {
            String[] parts = value.split(":");
            StringBuilder kept = new StringBuilder();
            for (int i = 0; i < Math.min(3, parts.length); i++) {
                kept.append(i == 0 ? "" : ":").append(parts[i]);
            }
            return kept + ":x";
        }
        return "unknown";
    }

    /** The browser name without control characters, at most 80 characters. */
    public static String cleanAgent(String agent) {
        if (agent == null || agent.isBlank()) {
            return "unknown";
        }
        String cleaned = agent.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
