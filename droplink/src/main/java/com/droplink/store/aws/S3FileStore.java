package com.droplink.store.aws;

import com.droplink.model.StoredFile;
import com.droplink.model.UploadTarget;
import com.droplink.store.FileStore;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Files in a private S3 bucket. The browser uploads and downloads through presigned addresses, so the bytes never
 * pass through the app. The key is used exactly as given: the caller adds any prefix.
 */
public class S3FileStore implements FileStore {

    static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    static final String DEFAULT_FILE_NAME = "download";
    /** The safe file name is always shorter than this. */
    static final int MAX_FILE_NAME_LENGTH = 149;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3FileStore(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public UploadTarget createUploadTarget(String key, String contentType, Duration validFor) {
        String type = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        // Content-Type is part of the signature: the browser must send exactly this value or S3 refuses the upload.
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(type)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .putObjectRequest(put)
                .build());
        return new UploadTarget("PUT", URI.create(presigned.url().toString()), Map.of("Content-Type", type));
    }

    @Override
    public Optional<StoredFile> head(String key) {
        try {
            HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            String type = response.contentType() == null ? DEFAULT_CONTENT_TYPE : response.contentType();
            return Optional.of(new StoredFile(response.contentLength() == null ? 0L : response.contentLength(), type));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public URI createDownloadUrl(String key, String fileName, boolean inline, String contentTypeOverride,
                                 Duration validFor) {
        GetObjectRequest.Builder get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition(inline ? "inline" : attachmentDisposition(fileName));
        if (contentTypeOverride != null) {
            get.responseContentType(contentTypeOverride);
        }
        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .getObjectRequest(get.build())
                .build());
        return URI.create(presigned.url().toString());
    }

    @Override
    public byte[] readStart(String key, int maxBytes) {
        if (maxBytes <= 0) {
            return new byte[0];
        }
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=0-" + (maxBytes - 1L))
                    .build());
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            return new byte[0];
        } catch (S3Exception e) {
            // 404: no such file. 416: the range does not fit, which is what S3 says for an empty (0 byte) file.
            if (e.statusCode() == 404 || e.statusCode() == 416) {
                return new byte[0];
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** attachment; filename="plain ascii" ; filename*=UTF-8''percent-encoded */
    static String attachmentDisposition(String fileName) {
        String safe = safeFileName(fileName);
        StringBuilder ascii = new StringBuilder();
        safe.codePoints().forEach(cp -> ascii.append(cp >= 0x20 && cp < 0x7f ? (char) cp : '_'));
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + rfc5987Encode(safe);
    }

    /**
     * A file name that is safe to put in a header: no CR, LF or other control characters, no quotes, no backslashes,
     * no path separators, shorter than 150 characters (the extension is kept when the name is cut), and "download"
     * when nothing is left.
     */
    static String safeFileName(String fileName) {
        if (fileName == null) {
            return DEFAULT_FILE_NAME;
        }
        StringBuilder sb = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '"' || c == '\\' || c == '/') {
                continue;
            }
            sb.append(c);
        }
        String name = sb.toString().strip();
        // only dots (".", "..") is not a usable name
        if (name.isEmpty() || name.chars().allMatch(ch -> ch == '.')) {
            return DEFAULT_FILE_NAME;
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = cut(name);
        }
        return name;
    }

    private static String cut(String name) {
        int dot = name.lastIndexOf('.');
        String extension = (dot > 0 && name.length() - dot <= 10) ? name.substring(dot) : "";
        String base = name.substring(0, MAX_FILE_NAME_LENGTH - extension.length());
        if (Character.isHighSurrogate(base.charAt(base.length() - 1))) {
            base = base.substring(0, base.length() - 1);   // do not cut an emoji in half
        }
        return base + extension;
    }

    /** RFC 5987 value: UTF-8 bytes, everything except letters, digits and !#$&+-.^_`|~ is written as %XX. */
    static String rfc5987Encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
