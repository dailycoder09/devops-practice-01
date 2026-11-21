package com.droplink.service;

import com.droplink.config.DropLinkProperties;
import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import com.droplink.model.StoredFile;
import com.droplink.model.UploadTarget;
import com.droplink.store.FileStore;
import com.droplink.store.LinkRepository;
import com.droplink.store.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The rules of DropLink. Everything that decides whether a person may upload, see, preview, download or delete is here,
 * so it can be tested without a web server and without AWS.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    static final Duration UPLOAD_VALID = Duration.ofMinutes(10);
    static final Duration DOWNLOAD_VALID = Duration.ofSeconds(60);
    static final Duration PREVIEW_VALID = Duration.ofMinutes(5);
    static final int TEXT_PREVIEW_BYTES = 64 * 1024;
    private static final int MAX_NAME_LENGTH = 150;
    private static final int MAX_DOWNLOADS_LIMIT = 1000;
    private static final int ACTIVITY_LIMIT = 50;
    private static final Pattern CONTENT_TYPE = Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");

    private final LinkRepository links;
    private final FileStore files;
    private final Notifier notifier;
    private final PasswordService passwords;
    private final PreviewPolicy previews;
    private final IdGenerator ids;
    private final DropLinkProperties properties;
    private final Clock clock;
    private final AttemptLimiter limiter = new AttemptLimiter();

    public LinkService(LinkRepository links, FileStore files, Notifier notifier, PasswordService passwords,
                       PreviewPolicy previews, IdGenerator ids, DropLinkProperties properties, Clock clock) {
        this.links = links;
        this.files = files;
        this.notifier = notifier;
        this.passwords = passwords;
        this.previews = previews;
        this.ids = ids;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- make a link

    public CreatedLink create(CreateLinkRequest request) {
        String fileName = cleanFileName(request.fileName());
        if (fileName.isEmpty()) {
            throw ApiException.badRequest("The file name is empty.");
        }
        if (request.size() < 1) {
            throw ApiException.badRequest("The file size must be at least 1 byte.");
        }
        if (request.size() > properties.maxUploadBytes()) {
            throw ApiException.badRequest("The file is bigger than the " + properties.getMaxUploadMb() + " MB allowed.");
        }
        if (!properties.getExpiryHours().contains(request.expiresInHours())) {
            throw ApiException.badRequest("That expiry is not offered.");
        }
        Integer maxDownloads = request.maxDownloads();
        if (maxDownloads != null && (maxDownloads < 1 || maxDownloads > MAX_DOWNLOADS_LIMIT)) {
            throw ApiException.badRequest("Allowed downloads must be between 1 and " + MAX_DOWNLOADS_LIMIT + ".");
        }
        String password = request.password();
        boolean hasPassword = password != null && !password.isEmpty();
        if (hasPassword && (password.length() < 4 || password.length() > 64)) {
            throw ApiException.badRequest("The password must have 4 to 64 characters.");
        }

        Instant now = clock.instant();
        String id = ids.newId();
        String manageToken = ids.newToken();
        String contentType = cleanContentType(request.contentType());
        Link link = new Link(id, fileName, contentType, request.size(), properties.getKeyPrefix() + id,
                now, now.plus(Duration.ofHours(request.expiresInHours())), maxDownloads, 0,
                hasPassword ? passwords.hash(password) : null, sha256(manageToken), LinkStatus.PENDING);
        links.create(link);
        UploadTarget upload = files.createUploadTarget(link.key(), contentType, UPLOAD_VALID);
        log.info("Link {}... created: {} bytes, expires in {} h, limit {}, password {}", shortId(id), request.size(),
                request.expiresInHours(), maxDownloads == null ? "none" : maxDownloads, hasPassword);
        return new CreatedLink(link, manageToken, upload);
    }

    /** The browser says the upload is done: check that the file is really there and not too big, then activate the link. */
    public Link complete(String id, String manageToken) {
        Link link = links.find(id).orElseThrow(ApiException::notFound);
        requireManage(link, manageToken);
        if (link.status() == LinkStatus.ACTIVE) {
            return link;
        }
        StoredFile stored = files.head(link.key())
                .orElseThrow(() -> ApiException.conflict("The upload has not arrived. Please try again."));
        if (stored.size() > properties.maxUploadBytes()) {
            files.delete(link.key());
            links.delete(id);
            throw ApiException.badRequest("The uploaded file is bigger than the " + properties.getMaxUploadMb() + " MB allowed.");
        }
        if (stored.size() < 1) {
            throw ApiException.badRequest("The uploaded file is empty.");
        }
        String type = stored.contentType() == null || stored.contentType().isBlank() ? link.contentType() : stored.contentType();
        links.activate(id, stored.size(), type);
        log.info("Link {}... is active: {} bytes", shortId(id), stored.size());
        return links.find(id).orElseThrow(ApiException::notFound);
    }

    // ---------------------------------------------------------------- open, preview, download

    /** The link as a visitor may see it: it must be ACTIVE, not expired, with downloads left. */
    public Link requireVisible(String id) {
        Link link = links.find(id).orElseThrow(ApiException::notFound);
        if (link.status() != LinkStatus.ACTIVE) {
            throw ApiException.notFound();
        }
        if (link.isExpired(clock.instant())) {
            throw ApiException.gone("This link has expired.");
        }
        if (!link.hasDownloadsLeft()) {
            throw ApiException.gone("This link has no downloads left.");
        }
        return link;
    }

    /** True when the password is right. After five wrong tries from one client the answer is 429 for a while. */
    public boolean checkPassword(String id, String password, String clientKey) {
        Link link = requireVisible(id);
        if (!link.hasPassword()) {
            return true;
        }
        Instant now = clock.instant();
        String key = clientKey + "|" + id;
        if (limiter.blocked(key, now)) {
            log.warn("Link {}... password refused: client {} is locked after too many wrong tries", shortId(id), ClientInfo.maskIp(clientKey));
            throw ApiException.tooManyAttempts();
        }
        if (passwords.matches(password, link.passwordHash())) {
            limiter.reset(key);
            log.info("Link {}... unlocked with the password", shortId(id));
            return true;
        }
        limiter.failed(key, now);
        int tries = limiter.recentFailures(key, now);
        log.warn("Link {}... wrong password from {} (try {} of {}){}", shortId(id), ClientInfo.maskIp(clientKey), tries,
                AttemptLimiter.maxFailures(), tries >= AttemptLimiter.maxFailures() ? ", client is now locked for 15 minutes" : "");
        return false;
    }

    /** Counts one download and returns the short-lived address the browser is sent to. */
    public URI download(String id, boolean unlocked, ClientInfo client) {
        Link link = requireVisible(id);
        requireUnlocked(link, unlocked);
        Instant now = clock.instant();
        long count = links.tryCountDownload(id, now)
                .orElseThrow(() -> ApiException.gone("This link has no downloads left or has expired."));
        try {
            links.addEvent(id, new DownloadEvent(now, ClientInfo.maskIp(client.ip()), ClientInfo.cleanAgent(client.userAgent())));
            notifier.downloaded(link, count);
        } catch (RuntimeException e) {
            log.warn("The activity or the notification of a download could not be written: {}", e.getClass().getSimpleName());
        }
        log.info("Link {}... downloaded ({} so far)", shortId(id), count);
        return files.createDownloadUrl(link.key(), link.fileName(), false, null, DOWNLOAD_VALID);
    }

    /** What kind of preview this link has (never a download). */
    public PreviewPolicy.Preview previewOf(Link link) {
        return previews.of(link.fileName());
    }

    /** An inline address for an image, PDF, video or audio file. It is not a download and is not counted. */
    public URI previewUrl(String id, boolean unlocked) {
        Link link = requireVisible(id);
        requireUnlocked(link, unlocked);
        PreviewPolicy.Preview preview = previews.of(link.fileName());
        if (preview.kind() == PreviewPolicy.Kind.NONE || preview.kind() == PreviewPolicy.Kind.TEXT) {
            throw ApiException.unsupported("There is no preview for this file. You can download it.");
        }
        return files.createDownloadUrl(link.key(), link.fileName(), true, preview.safeContentType(), PREVIEW_VALID);
    }

    /** The first 64 KB of a text or code file, to be shown as escaped text. */
    public TextPreview textPreview(String id, boolean unlocked) {
        Link link = requireVisible(id);
        requireUnlocked(link, unlocked);
        if (previews.of(link.fileName()).kind() != PreviewPolicy.Kind.TEXT) {
            throw ApiException.unsupported("There is no text preview for this file. You can download it.");
        }
        byte[] bytes = files.readStart(link.key(), TEXT_PREVIEW_BYTES + 1);
        boolean truncated = bytes.length > TEXT_PREVIEW_BYTES;
        int length = Math.min(bytes.length, TEXT_PREVIEW_BYTES);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return new TextPreview(decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString(), truncated);
        } catch (java.nio.charset.CharacterCodingException e) {
            return new TextPreview("", truncated);
        }
    }

    // ---------------------------------------------------------------- manage

    public Activity activity(String id, String manageToken) {
        Link link = links.find(id).orElseThrow(ApiException::notFound);
        requireManage(link, manageToken);
        return new Activity(link, links.events(id, ACTIVITY_LIMIT));
    }

    public void delete(String id, String manageToken) {
        Link link = links.find(id).orElseThrow(ApiException::notFound);
        requireManage(link, manageToken);
        files.delete(link.key());
        links.delete(id);
        log.info("Link {}... deleted by its creator", shortId(id));
    }

    /** The expiry choices and the size limit, for the page. */
    public DropLinkProperties settings() {
        return properties;
    }

    // ---------------------------------------------------------------- helpers

    private static void requireUnlocked(Link link, boolean unlocked) {
        if (link.hasPassword() && !unlocked) {
            throw ApiException.forbidden("This link needs its password.");
        }
    }

    private static void requireManage(Link link, String token) {
        boolean ok = token != null && MessageDigest.isEqual(
                sha256(token).getBytes(StandardCharsets.UTF_8), link.manageTokenHash().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw ApiException.forbidden("Wrong management key.");
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Only the name: no folders, no control characters, at most 150 characters (the ending is kept so the type stays visible). */
    static String cleanFileName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("\\p{Cntrl}", "");
        cleaned = cleaned.substring(Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\')) + 1).trim();
        if (cleaned.length() > MAX_NAME_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            String extension = dot > 0 && cleaned.length() - dot <= 20 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH - extension.length()) + extension;
        }
        return cleaned;
    }

    private static String cleanContentType(String type) {
        if (type == null) {
            return "application/octet-stream";
        }
        String base = type.split(";", 2)[0].trim().toLowerCase();
        return base.length() <= 100 && CONTENT_TYPE.matcher(base).matches() ? base : "application/octet-stream";
    }

    /** The first characters of an id, for the log: the whole id is the secret of the link. */
    private static String shortId(String id) {
        return id.length() <= 4 ? id : id.substring(0, 4);
    }
}
