package com.droplink.service;

import com.droplink.config.DropLinkProperties;
import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import com.droplink.store.Notifier;
import com.droplink.store.local.InMemoryLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of DropLink, with a fake file store and the in-memory link store. */
class LinkServiceTest {

    private static final Instant START = Instant.parse("2026-11-21T10:00:00Z");

    /** A clock the tests can move. */
    static class MovableClock extends Clock {
        Instant now = START;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final FakeFileStore files = new FakeFileStore();
    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();
    private final List<String> notified = new ArrayList<>();
    private final DropLinkProperties properties = new DropLinkProperties();
    private final ClientInfo client = new ClientInfo("203.0.113.77", "Mozilla/5.0 (Test)");
    private LinkService service;

    @BeforeEach
    void setUp() {
        properties.setMaxUploadMb(10);
        Notifier notifier = (link, downloads) -> notified.add(link.fileName() + ":" + downloads);
        service = new LinkService(repository, files, notifier, new PasswordService(), new PreviewPolicy(),
                new IdGenerator(), properties, clock);
    }

    private static CreateLinkRequest request(String name, long size, int hours, Integer max, String password) {
        return new CreateLinkRequest(name, "application/octet-stream", size, hours, max, password);
    }

    private CreatedLink makeActive(String name, String type, String content, int hours, Integer max, String password) {
        CreatedLink created = service.create(new CreateLinkRequest(name, type, content.length(), hours, max, password));
        files.upload(created.link().key(), content, type);
        service.complete(created.link().id(), created.manageToken());
        return created;
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_makesAPendingLink_withASecretIdAKeyAndAnUploadTarget() {
        CreatedLink created = service.create(request("holiday.png", 2048, 24, null, null));

        Link link = created.link();
        assertThat(link.id()).matches("[0-9A-Za-z]{12}");
        assertThat(link.status()).isEqualTo(LinkStatus.PENDING);
        assertThat(link.key()).isEqualTo("files/" + link.id());
        assertThat(link.fileName()).isEqualTo("holiday.png");
        assertThat(link.createdAt()).isEqualTo(START);
        assertThat(link.expiresAt()).isEqualTo(START.plus(Duration.ofHours(24)));
        assertThat(link.downloads()).isZero();
        assertThat(created.upload().method()).isEqualTo("PUT");
        assertThat(created.upload().headers()).containsEntry("Content-Type", "application/octet-stream");
        assertThat(created.upload().url().toString()).contains("files/" + link.id()).contains("ttl=600");
    }

    @Test
    void create_keepsOnlyAHashOfTheManageTokenAndOfThePassword() {
        CreatedLink created = service.create(request("a.txt", 10, 1, null, "open-sesame"));

        assertThat(created.manageToken()).hasSizeGreaterThanOrEqualTo(24);
        assertThat(created.link().manageTokenHash()).isNotEqualTo(created.manageToken()).hasSize(64);
        assertThat(created.link().passwordHash()).isNotNull().isNotEqualTo("open-sesame").startsWith("$2");
        assertThat(created.link().hasPassword()).isTrue();
    }

    @Test
    void create_refusesBadInput() {
        assertThatThrownBy(() -> service.create(request("", 10, 1, null, null))).isInstanceOf(ApiException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> service.create(request("a.txt", 0, 1, null, null))).isInstanceOf(ApiException.class).hasMessageContaining("size");
        assertThatThrownBy(() -> service.create(request("a.txt", 11L * 1024 * 1024, 1, null, null))).isInstanceOf(ApiException.class).hasMessageContaining("10 MB");
        assertThatThrownBy(() -> service.create(request("a.txt", 10, 5, null, null))).isInstanceOf(ApiException.class).hasMessageContaining("expiry");
        assertThatThrownBy(() -> service.create(request("a.txt", 10, 1, 0, null))).isInstanceOf(ApiException.class).hasMessageContaining("downloads");
        assertThatThrownBy(() -> service.create(request("a.txt", 10, 1, null, "abc"))).isInstanceOf(ApiException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> service.create(request("a.txt", 10, 1, null, "x".repeat(65)))).isInstanceOf(ApiException.class).hasMessageContaining("password");
    }

    @Test
    void create_cleansTheFileName() {
        assertThat(service.create(request("../../etc/passwd", 10, 1, null, null)).link().fileName()).isEqualTo("passwd");
        assertThat(service.create(request("C:\\Users\\ann\\cv.pdf", 10, 1, null, null)).link().fileName()).isEqualTo("cv.pdf");
        assertThat(service.create(request("bad\r\nname\u0000.txt", 10, 1, null, null)).link().fileName()).isEqualTo("badname.txt");
        assertThat(service.create(request("x".repeat(300) + ".txt", 10, 1, null, null)).link().fileName()).hasSizeLessThanOrEqualTo(150);
    }

    @Test
    void create_offersTheExpiryChoicesOfTheSettings() {
        assertThat(service.create(request("a.txt", 10, 1, null, null)).link().expiresAt()).isEqualTo(START.plusSeconds(3600));
        assertThat(service.create(request("a.txt", 10, 168, null, null)).link().expiresAt()).isEqualTo(START.plusSeconds(168 * 3600L));
    }

    // ---------------------------------------------------------------- complete

    @Test
    void complete_activatesTheLink_andRecordsTheRealSize() {
        CreatedLink created = service.create(request("a.txt", 5, 24, null, null));
        files.upload(created.link().key(), "hello world", "text/plain");

        Link done = service.complete(created.link().id(), created.manageToken());

        assertThat(done.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(done.size()).isEqualTo(11);
    }

    @Test
    void complete_needsTheUploadToBeThere_theRightToken_andIsRepeatable() {
        CreatedLink created = service.create(request("a.txt", 5, 24, null, null));

        assertThatThrownBy(() -> service.complete(created.link().id(), created.manageToken()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));

        files.upload(created.link().key(), "hello", "text/plain");
        assertThatThrownBy(() -> service.complete(created.link().id(), "wrong-token"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));

        service.complete(created.link().id(), created.manageToken());
        assertThat(service.complete(created.link().id(), created.manageToken()).status()).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void complete_deletesAFileThatIsBiggerThanAllowed() {
        CreatedLink created = service.create(request("a.bin", 5, 24, null, null));
        files.upload(created.link().key(), new byte[11 * 1024 * 1024], "application/octet-stream");

        assertThatThrownBy(() -> service.complete(created.link().id(), created.manageToken()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));

        assertThat(files.has(created.link().key())).isFalse();
        assertThat(repository.find(created.link().id())).isEmpty();
    }

    // ---------------------------------------------------------------- download

    @Test
    void aPendingOrUnknownLink_cannotBeDownloaded() {
        CreatedLink created = service.create(request("a.txt", 5, 24, null, null));

        assertThatThrownBy(() -> service.download(created.link().id(), true, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.download("doesnotexist", true, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void download_givesAShortAttachmentAddress_countsIt_recordsAnEvent_andNotifies() {
        CreatedLink created = makeActive("report.pdf", "application/pdf", "pdf-bytes", 24, null, null);

        URI url = service.download(created.link().id(), true, client);

        assertThat(url.toString()).contains("inline=false").contains("type=none").contains("ttl=60");
        Link after = service.requireVisible(created.link().id());
        assertThat(after.downloads()).isEqualTo(1);
        List<DownloadEvent> events = repository.events(created.link().id(), 10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).ip()).isEqualTo("203.0.113.x");
        assertThat(events.get(0).userAgent()).isEqualTo("Mozilla/5.0 (Test)");
        assertThat(notified).containsExactly("report.pdf:1");
    }

    @Test
    void aOneTimeLink_worksOnce_thenIsGone() {
        CreatedLink created = makeActive("secret.txt", "text/plain", "x", 24, 1, null);

        service.download(created.link().id(), true, client);

        assertThatThrownBy(() -> service.download(created.link().id(), true, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
        assertThat(repository.find(created.link().id()).orElseThrow().downloads()).isEqualTo(1);
    }

    @Test
    void aDownloadLimit_isRespected() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 24, 3, null);

        for (int i = 0; i < 3; i++) {
            service.download(created.link().id(), true, client);
        }

        assertThatThrownBy(() -> service.download(created.link().id(), true, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
    }

    @Test
    void anExpiredLink_isGone() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 1, null, null);
        clock.now = START.plus(Duration.ofHours(1)).plusSeconds(1);

        assertThatThrownBy(() -> service.download(created.link().id(), true, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
        assertThatThrownBy(() -> service.requireVisible(created.link().id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
    }

    @Test
    void aPasswordLink_needsTheUnlock() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 24, null, "open-sesame");

        assertThatThrownBy(() -> service.download(created.link().id(), false, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        assertThat(repository.find(created.link().id()).orElseThrow().downloads()).as("a refused download is not counted").isZero();

        assertThat(service.download(created.link().id(), true, client)).isNotNull();
    }

    // ---------------------------------------------------------------- password

    @Test
    void checkPassword_acceptsTheRightOneAndRefusesTheWrongOne() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 24, null, "open-sesame");

        assertThat(service.checkPassword(created.link().id(), "open-sesame", "client-1")).isTrue();
        assertThat(service.checkPassword(created.link().id(), "wrong-one", "client-1")).isFalse();
    }

    @Test
    void checkPassword_stopsAfterFiveWrongTries_untilTheWindowPasses() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 24, null, "open-sesame");

        for (int i = 0; i < 5; i++) {
            assertThat(service.checkPassword(created.link().id(), "wrong-" + i, "client-1")).isFalse();
        }

        assertThatThrownBy(() -> service.checkPassword(created.link().id(), "open-sesame", "client-1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(429));
        assertThat(service.checkPassword(created.link().id(), "open-sesame", "client-2")).as("another client is not blocked").isTrue();

        clock.now = START.plus(Duration.ofMinutes(16));
        assertThat(service.checkPassword(created.link().id(), "open-sesame", "client-1")).isTrue();
    }

    // ---------------------------------------------------------------- preview

    @Test
    void preview_ofAnImage_isInline_withASafeTypeOfOurOwn_andIsNotADownload() {
        CreatedLink created = makeActive("photo.JPG", "text/html", "jpeg-bytes", 24, 1, null);

        URI url = service.previewUrl(created.link().id(), true);

        assertThat(url.toString()).contains("inline=true").contains("type=image/jpeg").contains("ttl=300");
        assertThat(repository.find(created.link().id()).orElseThrow().downloads()).isZero();
    }

    @Test
    void preview_knowsImagesPdfVideoAndAudio() {
        PreviewPolicy policy = new PreviewPolicy();
        assertThat(policy.of("a.png").kind()).isEqualTo(PreviewPolicy.Kind.IMAGE);
        assertThat(policy.of("a.webp").safeContentType()).isEqualTo("image/webp");
        assertThat(policy.of("a.pdf").kind()).isEqualTo(PreviewPolicy.Kind.PDF);
        assertThat(policy.of("a.pdf").safeContentType()).isEqualTo("application/pdf");
        assertThat(policy.of("a.mp4").kind()).isEqualTo(PreviewPolicy.Kind.VIDEO);
        assertThat(policy.of("a.webm").safeContentType()).isEqualTo("video/webm");
        assertThat(policy.of("a.mp3").kind()).isEqualTo(PreviewPolicy.Kind.AUDIO);
        assertThat(policy.of("a.wav").safeContentType()).isEqualTo("audio/wav");
        assertThat(policy.of("notes.md").kind()).isEqualTo(PreviewPolicy.Kind.TEXT);
        assertThat(policy.of("data.csv").kind()).isEqualTo(PreviewPolicy.Kind.TEXT);
        assertThat(policy.of("App.java").kind()).isEqualTo(PreviewPolicy.Kind.TEXT);
        assertThat(policy.of("Makefile").kind()).isEqualTo(PreviewPolicy.Kind.NONE);
    }

    @Test
    void preview_neverShowsHtmlSvgOrProgramsInline_theyAreTextOrNothing() {
        PreviewPolicy policy = new PreviewPolicy();
        assertThat(policy.of("page.html").kind()).as("html is shown as escaped text, never run").isEqualTo(PreviewPolicy.Kind.TEXT);
        assertThat(policy.of("logo.svg").kind()).as("svg can hold scripts").isEqualTo(PreviewPolicy.Kind.NONE);
        assertThat(policy.of("setup.exe").kind()).isEqualTo(PreviewPolicy.Kind.NONE);
        assertThat(policy.of("archive.zip").kind()).isEqualTo(PreviewPolicy.Kind.NONE);
        assertThat(policy.of("noextension").kind()).isEqualTo(PreviewPolicy.Kind.NONE);

        CreatedLink created = makeActive("page.html", "text/html", "<script>alert(1)</script>", 24, null, null);
        assertThatThrownBy(() -> service.previewUrl(created.link().id(), true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(415));
    }

    @Test
    void preview_ofText_isReadFromTheStart_andCutAt64Kilobytes() {
        CreatedLink small = makeActive("notes.txt", "text/plain", "line one\nline two", 24, null, null);
        TextPreview shown = service.textPreview(small.link().id(), true);
        assertThat(shown.text()).isEqualTo("line one\nline two");
        assertThat(shown.truncated()).isFalse();

        CreatedLink big = service.create(new CreateLinkRequest("big.log", "text/plain", 200_000, 24, null, null));
        files.upload(big.link().key(), "a".repeat(200_000), "text/plain");
        service.complete(big.link().id(), big.manageToken());
        TextPreview cut = service.textPreview(big.link().id(), true);
        assertThat(cut.text()).hasSize(64 * 1024);
        assertThat(cut.truncated()).isTrue();
    }

    @Test
    void preview_needsTheUnlockForAPasswordLink_andIsRefusedWhenExpired() {
        CreatedLink created = makeActive("photo.png", "image/png", "x", 1, null, "open-sesame");

        assertThatThrownBy(() -> service.previewUrl(created.link().id(), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        assertThat(service.previewUrl(created.link().id(), true)).isNotNull();

        clock.now = START.plus(Duration.ofHours(2));
        assertThatThrownBy(() -> service.previewUrl(created.link().id(), true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
    }

    // ---------------------------------------------------------------- manage

    @Test
    void activity_showsTheDownloadsNewestFirst_andNeedsTheToken() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 24, null, null);
        service.download(created.link().id(), true, client);
        clock.now = START.plusSeconds(60);
        service.download(created.link().id(), true, new ClientInfo("198.51.100.9", "curl/8"));

        Activity activity = service.activity(created.link().id(), created.manageToken());

        assertThat(activity.link().downloads()).isEqualTo(2);
        assertThat(activity.events()).extracting(DownloadEvent::userAgent).containsExactly("curl/8", "Mozilla/5.0 (Test)");
        assertThatThrownBy(() -> service.activity(created.link().id(), "wrong"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
    }

    @Test
    void delete_removesTheFileAndTheLink_andNeedsTheToken() {
        CreatedLink created = makeActive("a.txt", "text/plain", "x", 24, null, null);

        assertThatThrownBy(() -> service.delete(created.link().id(), "wrong"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        assertThat(files.has(created.link().key())).isTrue();

        service.delete(created.link().id(), created.manageToken());

        assertThat(files.has(created.link().key())).isFalse();
        assertThatThrownBy(() -> service.download(created.link().id(), true, client))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void anIpAddress_isShownWithItsLastPartHidden_andAnIpv6AddressToo() {
        assertThat(ClientInfo.maskIp("203.0.113.77")).isEqualTo("203.0.113.x");
        assertThat(ClientInfo.maskIp("2001:db8:85a3:0:0:8a2e:370:7334")).isEqualTo("2001:db8:85a3:x");
        assertThat(ClientInfo.maskIp(null)).isEqualTo("unknown");
        assertThat(ClientInfo.maskIp("garbage")).isEqualTo("unknown");
    }
}
