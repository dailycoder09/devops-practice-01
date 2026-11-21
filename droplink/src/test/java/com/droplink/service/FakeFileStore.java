package com.droplink.service;

import com.droplink.model.StoredFile;
import com.droplink.model.UploadTarget;
import com.droplink.store.FileStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A file store in memory for the tests: "uploading" is a method call, and the signed addresses are plain text. */
class FakeFileStore implements FileStore {

    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, String> types = new HashMap<>();

    void upload(String key, byte[] bytes, String contentType) {
        files.put(key, bytes);
        types.put(key, contentType);
    }

    void upload(String key, String text, String contentType) {
        upload(key, text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    boolean has(String key) {
        return files.containsKey(key);
    }

    @Override
    public UploadTarget createUploadTarget(String key, String contentType, Duration validFor) {
        return new UploadTarget("PUT", URI.create("http://fake/upload/" + key + "?ttl=" + validFor.toSeconds()),
                Map.of("Content-Type", contentType));
    }

    @Override
    public Optional<StoredFile> head(String key) {
        return files.containsKey(key) ? Optional.of(new StoredFile(files.get(key).length, types.get(key))) : Optional.empty();
    }

    @Override
    public URI createDownloadUrl(String key, String fileName, boolean inline, String contentTypeOverride, Duration validFor) {
        return URI.create("http://fake/get/" + key + "?inline=" + inline + "&type=" + (contentTypeOverride == null ? "none" : contentTypeOverride)
                + "&ttl=" + validFor.toSeconds());
    }

    @Override
    public byte[] readStart(String key, int maxBytes) {
        byte[] all = files.getOrDefault(key, new byte[0]);
        return Arrays.copyOf(all, Math.min(all.length, maxBytes));
    }

    @Override
    public void delete(String key) {
        files.remove(key);
        types.remove(key);
    }
}
