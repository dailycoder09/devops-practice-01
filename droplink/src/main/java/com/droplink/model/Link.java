package com.droplink.model;

import java.time.Instant;

/**
 * One shared file.
 *
 * @param id              the secret part of the address (12 random letters and digits); anyone who has it can open the link
 * @param fileName        the original name, shown to people and used for the download
 * @param contentType     the type the browser reported (never trusted for preview, see PreviewPolicy)
 * @param size            bytes; the declared size until the upload is confirmed, then the real size of the stored file
 * @param key             where the file is stored (S3 key or local path under the data folder)
 * @param createdAt       when the link was made
 * @param expiresAt       after this moment no download or preview is allowed
 * @param maxDownloads    how many downloads are allowed, or null for no limit (1 = one-time link)
 * @param downloads       how many downloads were counted so far
 * @param passwordHash    BCrypt hash, or null when the link has no password
 * @param manageTokenHash SHA-256 hex of the secret that lets the creator see the activity and delete the link
 * @param status          PENDING or ACTIVE
 */
public record Link(String id, String fileName, String contentType, long size, String key,
                   Instant createdAt, Instant expiresAt, Integer maxDownloads, long downloads,
                   String passwordHash, String manageTokenHash, LinkStatus status) {

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean hasDownloadsLeft() {
        return maxDownloads == null || downloads < maxDownloads;
    }
}
