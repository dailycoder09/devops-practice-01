package com.droplink.store.aws;

import com.droplink.model.UploadTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: runs against MinIO (or any S3 endpoint, path-style) when TEST_S3_ENDPOINT is set. The bucket is
 * TEST_S3_BUCKET (created here when missing). Fake or MinIO credentials in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.
 * Every key is under a random prefix and removed at the end. See docs/EMULATOR-TESTS.md.
 */
@EnabledIfEnvironmentVariable(named = "TEST_S3_ENDPOINT", matches = ".+")
class S3FileStoreEmulatorTest {

    private static S3Client s3;
    private static S3Presigner presigner;
    private static S3FileStore store;
    private static HttpClient http;
    private static final String PREFIX = "droplink-test-" + UUID.randomUUID().toString().substring(0, 8) + "/";

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create(System.getenv("TEST_S3_ENDPOINT"));
        String bucket = System.getenv().getOrDefault("TEST_S3_BUCKET", "droplink-test");
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-west-2"));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3 = S3Client.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(DefaultCredentialsProvider.create()).serviceConfiguration(pathStyle).build();
        presigner = S3Presigner.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(DefaultCredentialsProvider.create()).serviceConfiguration(pathStyle).build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // fine: it is there already
        }
        store = new S3FileStore(s3, presigner, bucket);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (s3 != null) {
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
    }

    private static HttpResponse<byte[]> put(UploadTarget target, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(target.url()).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> get(URI url) throws Exception {
        return http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void fullLifecycle() throws Exception {
        String key = PREFIX + "life/report v1.txt";
        byte[] content = "Hello DropLink, this is the content of the file.".getBytes(StandardCharsets.UTF_8);

        assertThat(store.head(key)).as("nothing uploaded yet").isEmpty();
        assertThat(store.readStart(key, 100)).as("missing key").isEmpty();

        // upload through the presigned PUT, as the browser does
        UploadTarget target = store.createUploadTarget(key, "text/plain", Duration.ofMinutes(5));
        assertThat(target.method()).isEqualTo("PUT");
        assertThat(target.headers()).containsOnlyKeys("Content-Type");
        HttpResponse<byte[]> uploaded = put(target, content, target.headers().get("Content-Type"));
        assertThat(uploaded.statusCode()).isEqualTo(200);

        // head
        assertThat(store.head(key)).hasValueSatisfying(f -> {
            assertThat(f.size()).isEqualTo(content.length);
            assertThat(f.contentType()).isEqualTo("text/plain");
        });

        // readStart
        assertThat(store.readStart(key, 5)).isEqualTo("Hello".getBytes(StandardCharsets.UTF_8));
        assertThat(store.readStart(key, 100000)).isEqualTo(content);

        // presigned GET, attachment
        HttpResponse<byte[]> attachment = get(store.createDownloadUrl(key, "Résumé \"final\".txt", false, null, Duration.ofMinutes(5)));
        assertThat(attachment.statusCode()).isEqualTo(200);
        assertThat(attachment.body()).isEqualTo(content);
        assertThat(attachment.headers().firstValue("Content-Disposition").orElse(""))
                .isEqualTo("attachment; filename=\"R_sum_ final.txt\"; filename*=UTF-8''R%C3%A9sum%C3%A9%20final.txt");
        assertThat(attachment.headers().firstValue("Content-Type")).contains("text/plain");

        // presigned GET, inline with a safe type forced by the app
        HttpResponse<byte[]> inline = get(store.createDownloadUrl(key, "ignored.txt", true, "application/octet-stream", Duration.ofMinutes(5)));
        assertThat(inline.statusCode()).isEqualTo(200);
        assertThat(inline.headers().firstValue("Content-Disposition")).contains("inline");
        assertThat(inline.headers().firstValue("Content-Type")).contains("application/octet-stream");

        // delete (and delete again: missing is fine)
        store.delete(key);
        assertThat(store.head(key)).isEmpty();
        assertThat(store.readStart(key, 10)).isEmpty();
        store.delete(key);
    }

    @Test
    void uploadWithAnotherContentTypeIsRefused() throws Exception {
        String key = PREFIX + "signed/type.bin";
        UploadTarget target = store.createUploadTarget(key, "image/png", Duration.ofMinutes(5));

        HttpResponse<byte[]> wrong = put(target, new byte[]{1, 2, 3}, "text/html");
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(store.head(key)).isEmpty();

        HttpResponse<byte[]> right = put(target, new byte[]{1, 2, 3}, "image/png");
        assertThat(right.statusCode()).isEqualTo(200);
        assertThat(store.head(key)).hasValueSatisfying(f -> assertThat(f.contentType()).isEqualTo("image/png"));
        store.delete(key);
    }

    @Test
    void emptyFileCanBeReadWithoutError() throws Exception {
        String key = PREFIX + "empty/zero.bin";
        UploadTarget target = store.createUploadTarget(key, "application/octet-stream", Duration.ofMinutes(5));
        assertThat(put(target, new byte[0], "application/octet-stream").statusCode()).isEqualTo(200);

        assertThat(store.head(key)).hasValueSatisfying(f -> assertThat(f.size()).isZero());
        assertThat(store.readStart(key, 100)).isEmpty();
        store.delete(key);
    }

    @Test
    void expiredUrlIsRefused() throws Exception {
        String key = PREFIX + "expiry/a.txt";
        UploadTarget target = store.createUploadTarget(key, "text/plain", Duration.ofMinutes(5));
        put(target, "x".getBytes(StandardCharsets.UTF_8), "text/plain");

        URI url = store.createDownloadUrl(key, "a.txt", false, null, Duration.ofSeconds(1));
        assertThat(get(url).statusCode()).isEqualTo(200);
        Thread.sleep(2500);
        assertThat(get(url).statusCode()).isEqualTo(403);
        store.delete(key);
    }
}
