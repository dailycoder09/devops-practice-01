package com.droplink.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides what can be previewed, from the file NAME only (the type the uploader claims is never trusted).
 *
 * Images, PDF, video and audio are shown by the browser from a signed address, and always with a type chosen here,
 * so an uploaded file can never be served as a page that runs scripts. Text and code are read by the app and shown
 * as escaped text, which includes html files: they are shown as text, never run. SVG is not previewed at all,
 * because it can contain scripts.
 */
@Component
public class PreviewPolicy {

    public enum Kind { IMAGE, PDF, VIDEO, AUDIO, TEXT, NONE }

    public record Preview(Kind kind, String safeContentType) {
    }

    private static final Preview NONE = new Preview(Kind.NONE, null);

    private static final Map<String, Preview> MEDIA = Map.ofEntries(
            Map.entry("png", new Preview(Kind.IMAGE, "image/png")),
            Map.entry("jpg", new Preview(Kind.IMAGE, "image/jpeg")),
            Map.entry("jpeg", new Preview(Kind.IMAGE, "image/jpeg")),
            Map.entry("gif", new Preview(Kind.IMAGE, "image/gif")),
            Map.entry("webp", new Preview(Kind.IMAGE, "image/webp")),
            Map.entry("bmp", new Preview(Kind.IMAGE, "image/bmp")),
            Map.entry("avif", new Preview(Kind.IMAGE, "image/avif")),
            Map.entry("pdf", new Preview(Kind.PDF, "application/pdf")),
            Map.entry("mp4", new Preview(Kind.VIDEO, "video/mp4")),
            Map.entry("m4v", new Preview(Kind.VIDEO, "video/mp4")),
            Map.entry("webm", new Preview(Kind.VIDEO, "video/webm")),
            Map.entry("ogv", new Preview(Kind.VIDEO, "video/ogg")),
            Map.entry("mp3", new Preview(Kind.AUDIO, "audio/mpeg")),
            Map.entry("wav", new Preview(Kind.AUDIO, "audio/wav")),
            Map.entry("ogg", new Preview(Kind.AUDIO, "audio/ogg")),
            Map.entry("oga", new Preview(Kind.AUDIO, "audio/ogg")),
            Map.entry("m4a", new Preview(Kind.AUDIO, "audio/mp4")));

    private static final Set<String> TEXT = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yml", "yaml", "log", "ini", "conf", "properties",
            "html", "htm", "css", "js", "ts", "java", "kt", "py", "sh", "sql", "c", "h", "cpp", "go", "rs", "rb", "php", "toml");

    public Preview of(String fileName) {
        if (fileName == null) {
            return NONE;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return NONE;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        Preview media = MEDIA.get(extension);
        if (media != null) {
            return media;
        }
        return TEXT.contains(extension) ? new Preview(Kind.TEXT, "text/plain; charset=utf-8") : NONE;
    }
}
