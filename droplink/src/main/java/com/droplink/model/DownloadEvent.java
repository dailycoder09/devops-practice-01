package com.droplink.model;

import java.time.Instant;

/**
 * One counted download, for the activity page.
 *
 * @param at        when
 * @param ip        the address of the downloader with its last part hidden (203.0.113.x), never the full address
 * @param userAgent the browser name, cut to 80 characters
 */
public record DownloadEvent(Instant at, String ip, String userAgent) {
}
