package com.droplink.store.aws;

import com.droplink.model.UploadTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.core.ResponseBytes;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Offline: a real presigner with fake keys (nothing is sent anywhere) and a mocked S3Client. */
class S3FileStoreTest {

    private S3Presigner presigner;
    private S3Client s3;
    private S3FileStore store;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.US_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAFAKEFAKEFAKE", "fakesecretfakesecretfakesecret")))
                .build();
        s3 = mock(S3Client.class);
        store = new S3FileStore(s3, presigner, "my-bucket");
    }

    // ---- upload ------------------------------------------------------------------------------------------------

    @Test
    void uploadTargetIsASignedPutWithTheContentTypeHeader() {
        UploadTarget target = store.createUploadTarget("files/abc/report.pdf", "application/pdf", Duration.ofMinutes(10));

        assertThat(target.method()).isEqualTo("PUT");
        assertThat(target.headers()).containsOnlyKeys("Content-Type").containsEntry("Content-Type", "application/pdf");
        URI url = target.url();
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("my-bucket.s3.us-west-2.amazonaws.com");
        assertThat(url.getRawPath()).isEqualTo("/files/abc/report.pdf");

        Map<String, String> q = query(url);
        assertThat(q.get("X-Amz-Signature")).matches("[0-9a-f]{64}");
        assertThat(q.get("X-Amz-Expires")).isEqualTo("600");
        assertThat(q.get("X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(q.get("X-Amz-SignedHeaders").split(";")).contains("content-type", "host");
        // the browser must be able to upload with ONLY the Content-Type header: nothing else may be signed
        assertThat(q.get("X-Amz-SignedHeaders").split(";")).containsExactlyInAnyOrder("content-type", "host");
        assertThat(q.keySet()).noneMatch(k -> k.toLowerCase().contains("checksum"));
    }

    @Test
    void differentContentTypeGivesDifferentSignature() {
        String a = query(store.createUploadTarget("k", "text/plain", Duration.ofMinutes(5)).url()).get("X-Amz-Signature");
        String b = query(store.createUploadTarget("k", "image/png", Duration.ofMinutes(5)).url()).get("X-Amz-Signature");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void blankContentTypeFallsBackToOctetStream() {
        UploadTarget target = store.createUploadTarget("k", " ", Duration.ofMinutes(5));
        assertThat(target.headers()).containsOnly(Map.entry("Content-Type", "application/octet-stream"));
    }

    @Test
    void keyIsUsedExactlyAsGiven() {
        UploadTarget target = store.createUploadTarget("a/b c/ü.txt", "text/plain", Duration.ofMinutes(5));
        assertThat(URI.create(target.url().toString()).getPath()).isEqualTo("/a/b c/ü.txt");
    }

    // ---- download ----------------------------------------------------------------------------------------------

    @Test
    void inlineDownloadHasInlineDispositionOnly() {
        URI url = store.createDownloadUrl("files/x", "ignored.png", true, "image/png", Duration.ofMinutes(5));
        Map<String, String> q = query(url);

        assertThat(q.get("response-content-disposition")).isEqualTo("inline");
        assertThat(q.get("response-content-type")).isEqualTo("image/png");
        assertThat(q.get("X-Amz-Signature")).isNotBlank();
        assertThat(q.get("X-Amz-Expires")).isEqualTo("300");
        assertThat(url.getHost()).isEqualTo("my-bucket.s3.us-west-2.amazonaws.com");
    }

    @Test
    void attachmentDownloadHasBothFilenameForms() {
        URI url = store.createDownloadUrl("files/x", "Résumé 2024.pdf", false, null, Duration.ofMinutes(5));
        Map<String, String> q = query(url);

        assertThat(q.get("response-content-disposition"))
                .isEqualTo("attachment; filename=\"R_sum_ 2024.pdf\"; filename*=UTF-8''R%C3%A9sum%C3%A9%202024.pdf");
        assertThat(q).doesNotContainKey("response-content-type");
    }

    @Test
    void plainAsciiNameIsKept() {
        Map<String, String> q = query(store.createDownloadUrl("k", "report.pdf", false, "application/octet-stream", Duration.ofMinutes(1)));
        assertThat(q.get("response-content-disposition"))
                .isEqualTo("attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf");
        assertThat(q.get("response-content-type")).isEqualTo("application/octet-stream");
    }

    @Test
    void headerInjectionInTheFileNameIsRemoved() {
        Map<String, String> q = query(store.createDownloadUrl("k", "a\r\nSet-Cookie: x=1\"; \\evil/..\\x.txt", false, null, Duration.ofMinutes(1)));
        String disposition = q.get("response-content-disposition");
        assertThat(disposition).doesNotContain("\r").doesNotContain("\n").doesNotContain("\\").doesNotContain("/");
        // only the two quotes of filename="..." remain
        assertThat(disposition.chars().filter(c -> c == '"').count()).isEqualTo(2);
        assertThat(disposition).startsWith("attachment; filename=\"aSet-Cookie: x=1; evil..x.txt\"");
    }

    @Test
    void safeFileNameRules() {
        assertThat(S3FileStore.safeFileName("a\r\nb")).isEqualTo("ab");
        assertThat(S3FileStore.safeFileName("say \"hi\".txt")).isEqualTo("say hi.txt");
        assertThat(S3FileStore.safeFileName("..\\..\\dir/file.txt")).isEqualTo("....dirfile.txt");
        assertThat(S3FileStore.safeFileName("tab\there")).isEqualTo("tabhere");
        assertThat(S3FileStore.safeFileName("")).isEqualTo("download");
        assertThat(S3FileStore.safeFileName("   ")).isEqualTo("download");
        assertThat(S3FileStore.safeFileName(null)).isEqualTo("download");
        assertThat(S3FileStore.safeFileName("\r\n\"\\/")).isEqualTo("download");
        assertThat(S3FileStore.safeFileName("..")).isEqualTo("download");
    }

    @Test
    void longNamesAreCutBelow150AndKeepTheExtension() {
        String longName = "x".repeat(400) + ".pdf";
        String safe = S3FileStore.safeFileName(longName);
        assertThat(safe.length()).isLessThan(150);
        assertThat(safe).endsWith(".pdf").startsWith("xxxx");

        String noExtension = S3FileStore.safeFileName("y".repeat(500));
        assertThat(noExtension.length()).isLessThan(150);

        // an emoji (2 chars) must not be cut in half at the border
        String emoji = S3FileStore.safeFileName("a".repeat(148) + "\uD83D\uDE00\uD83D\uDE00");
        assertThat(emoji.length()).isLessThan(150);
        assertThat(Character.isHighSurrogate(emoji.charAt(emoji.length() - 1))).isFalse();
    }

    @Test
    void emptyNameFallsBackToDownloadInTheUrl() {
        Map<String, String> q = query(store.createDownloadUrl("k", "\r\n", false, null, Duration.ofMinutes(1)));
        assertThat(q.get("response-content-disposition"))
                .isEqualTo("attachment; filename=\"download\"; filename*=UTF-8''download");
    }

    // ---- head / readStart / delete (mocked client) -------------------------------------------------------------

    @Test
    void headReturnsSizeAndType() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().contentLength(42L).contentType("text/plain").build());
        assertThat(store.head("k")).hasValueSatisfying(f -> {
            assertThat(f.size()).isEqualTo(42L);
            assertThat(f.contentType()).isEqualTo("text/plain");
        });
    }

    @Test
    void headIsEmptyWhenMissing() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        assertThat(store.head("k")).isEmpty();

        S3Client other = mock(S3Client.class);
        when(other.headObject(any(HeadObjectRequest.class))).thenThrow((S3Exception) S3Exception.builder().statusCode(404).build());
        assertThat(new S3FileStore(other, presigner, "b").head("k")).isEmpty();
    }

    @Test
    void headOtherErrorsPropagate() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow((S3Exception) S3Exception.builder().statusCode(403).build());
        assertThatThrownBy(() -> store.head("k")).isInstanceOf(S3Exception.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readStartAsksForTheRange() {
        ResponseBytes<GetObjectResponse> bytes = ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[]{1, 2, 3});
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(bytes);

        assertThat(store.readStart("files/k", 512)).containsExactly(1, 2, 3);

        var captor = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(captor.capture());
        assertThat(captor.getValue().range()).isEqualTo("bytes=0-511");
        assertThat(captor.getValue().bucket()).isEqualTo("my-bucket");
        assertThat(captor.getValue().key()).isEqualTo("files/k");
    }

    @Test
    void readStartOfMissingOrEmptyFileIsEmpty() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        assertThat(store.readStart("k", 10)).isEmpty();

        S3Client empty = mock(S3Client.class);
        when(empty.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow((S3Exception) S3Exception.builder().statusCode(416).build());
        assertThat(new S3FileStore(empty, presigner, "b").readStart("k", 10)).isEmpty();

        assertThat(store.readStart("k", 0)).isEmpty();
    }

    @Test
    void readStartOtherErrorsPropagate() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow((S3Exception) S3Exception.builder().statusCode(500).build());
        assertThatThrownBy(() -> store.readStart("k", 10)).isInstanceOf(S3Exception.class);
    }

    @Test
    void deleteCallsDeleteObject() {
        store.delete("files/k");
        var captor = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("my-bucket");
        assertThat(captor.getValue().key()).isEqualTo("files/k");
    }

    // ---- helper ------------------------------------------------------------------------------------------------

    static Map<String, String> query(URI url) {
        return Arrays.stream(url.getRawQuery().split("&"))
                .map(p -> p.split("=", 2))
                .collect(Collectors.toMap(
                        p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p -> p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : ""));
    }
}
