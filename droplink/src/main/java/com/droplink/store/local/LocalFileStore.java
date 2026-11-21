package com.droplink.store.local;

import com.droplink.config.DropLinkProperties;
import com.droplink.model.StoredFile;
import com.droplink.model.UploadTarget;
import com.droplink.store.FileStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Files in a folder (local mode). The "signed addresses" are tokens that point back to this app
 * (/local-store/upload/... and /local-store/download/...), so the browser code is the same as with S3.
 */
public class LocalFileStore implements FileStore {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9/_.-]{1,200}");

    private final Path base;
    private final LocalUrlSigner signer;
    private final Clock clock;

    public LocalFileStore(DropLinkProperties properties, LocalUrlSigner signer, Clock clock) {
        this.base = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public UploadTarget createUploadTarget(String key, String contentType, Duration validFor) {
        resolve(key);
        String token = signer.sign(List.of("u", key, String.valueOf(clock.instant().plus(validFor).getEpochSecond()), contentType));
        return new UploadTarget("PUT", URI.create("/local-store/upload/" + token), Map.of("Content-Type", contentType));
    }

    @Override
    public Optional<StoredFile> head(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Path typeFile = typeFile(file);
            String type = Files.isRegularFile(typeFile) ? Files.readString(typeFile, StandardCharsets.UTF_8).trim() : "application/octet-stream";
            return Optional.of(new StoredFile(Files.size(file), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public URI createDownloadUrl(String key, String fileName, boolean inline, String contentTypeOverride, Duration validFor) {
        resolve(key);
        String token = signer.sign(List.of("d", key, String.valueOf(clock.instant().plus(validFor).getEpochSecond()),
                fileName == null ? "download" : fileName, inline ? "1" : "0", contentTypeOverride == null ? "" : contentTypeOverride));
        return URI.create("/local-store/download/" + token);
    }

    @Override
    public byte[] readStart(String key, int maxBytes) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return new byte[0];
        }
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(maxBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        Path file = resolve(key);
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(typeFile(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The path of a stored file, or an exception when the key could point outside the data folder. */
    public Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Unsafe storage key");
        }
        Path path = base.resolve(key).normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("Unsafe storage key");
        }
        return path;
    }

    public Path typeFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".type");
    }

    public LocalUrlSigner signer() {
        return signer;
    }

    /** For the tests and the cleanup: the folder that holds everything. */
    public Path base() {
        return base;
    }

    @Override
    public String toString() {
        return "LocalFileStore" + Arrays.asList(base.toString());
    }
}
