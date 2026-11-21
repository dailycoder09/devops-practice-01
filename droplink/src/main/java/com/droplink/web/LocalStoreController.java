package com.droplink.web;

import com.droplink.config.DropLinkProperties;
import com.droplink.service.ApiException;
import com.droplink.store.local.LocalFileStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;

/**
 * Local mode only: stands in for S3. A signed token (made by LocalFileStore) says which file may be written or read,
 * until when, and how it is shown. Without a valid, unexpired token nothing is served. Range requests work, so videos can be skipped through.
 */
@RestController
@RequestMapping("/local-store")
@ConditionalOnProperty(name = "droplink.mode", havingValue = "local", matchIfMissing = true)
public class LocalStoreController {

    private final LocalFileStore store;
    private final DropLinkProperties properties;
    private final Clock clock;

    public LocalStoreController(LocalFileStore store, DropLinkProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @PutMapping("/upload/{token}")
    public ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) throws IOException {
        List<String> fields = verified(token, "u", 4);
        String signedType = fields.get(3);
        String sentType = request.getContentType() == null ? "" : request.getContentType().split(";", 2)[0].trim().toLowerCase();
        if (!signedType.equalsIgnoreCase(sentType)) {
            throw ApiException.forbidden("The Content-Type of the upload does not match the signed address.");
        }
        Path file = store.resolve(fields.get(1));
        Files.createDirectories(file.getParent());
        long max = properties.maxUploadBytes();
        long written = 0;
        try (InputStream in = request.getInputStream(); OutputStream out = Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > max + 1) {      // one byte over is enough to be refused later; stop reading more
                    break;
                }
                out.write(buffer, 0, read);
            }
        }
        Files.writeString(store.typeFile(file), signedType, StandardCharsets.UTF_8);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/download/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        List<String> fields = verified(token, "d", 6);
        Path file = store.resolve(fields.get(1));
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound();
        }
        String fileName = fields.get(3);
        boolean inline = "1".equals(fields.get(4));
        String override = fields.get(5);
        String storedType = store.head(fields.get(1)).map(f -> f.contentType()).orElse("application/octet-stream");
        MediaType type = MediaType.parseMediaType(override.isEmpty() ? storedType : override);
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(type)
                .body(new FileSystemResource(file));
    }

    private List<String> verified(String token, String kind, int fieldCount) {
        List<String> fields = store.signer().verify(token).orElseThrow(() -> ApiException.forbidden("This address is not valid."));
        if (fields.size() != fieldCount || !kind.equals(fields.get(0))) {
            throw ApiException.forbidden("This address is not valid.");
        }
        long expires;
        try {
            expires = Long.parseLong(fields.get(2));
        } catch (NumberFormatException e) {
            throw ApiException.forbidden("This address is not valid.");
        }
        if (clock.instant().getEpochSecond() > expires) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This address has expired.");
        }
        return fields;
    }
}
