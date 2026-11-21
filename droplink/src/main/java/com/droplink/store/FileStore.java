package com.droplink.store;

import com.droplink.model.StoredFile;
import com.droplink.model.UploadTarget;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Where the file bytes are kept (a folder in local mode, a private S3 bucket in aws mode). The browser talks to the
 * store directly through the addresses made here, so big files never pass through the app.
 */
public interface FileStore {

    /**
     * An address for ONE upload of this file.
     *
     * @param key         where the file will be stored
     * @param contentType must be sent by the browser as the Content-Type header; part of the signature in aws mode
     * @param validFor    how long the address works
     */
    UploadTarget createUploadTarget(String key, String contentType, Duration validFor);

    /** Size and type of the stored file, or empty when nothing was uploaded under this key. */
    Optional<StoredFile> head(String key);

    /**
     * A short-lived address to read the file.
     *
     * @param fileName            the name the browser should save the file as (only for attachment)
     * @param inline              true to show it in the page (preview), false to make the browser save it
     * @param contentTypeOverride when not null, the answer carries exactly this Content-Type (used so that a
     *                            preview is always served as a safe type, whatever the uploader claimed)
     * @param validFor            how long the address works
     */
    URI createDownloadUrl(String key, String fileName, boolean inline, String contentTypeOverride, Duration validFor);

    /** The first {@code maxBytes} bytes of the file (all of it when it is smaller). */
    byte[] readStart(String key, int maxBytes);

    /** Removes the file. Missing is fine. */
    void delete(String key);
}
