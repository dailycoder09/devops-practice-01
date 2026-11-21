package com.droplink.service;

/**
 * What the user asks for when making a link.
 *
 * @param fileName      the name of the file
 * @param contentType   the type the browser reported, may be empty
 * @param size          the size in bytes
 * @param expiresInHours one of the offered expiry choices
 * @param maxDownloads  null for no limit, 1 for a one-time link
 * @param password      null or empty for no password, else 4 to 64 characters
 */
public record CreateLinkRequest(String fileName, String contentType, long size, int expiresInHours,
                                Integer maxDownloads, String password) {
}
