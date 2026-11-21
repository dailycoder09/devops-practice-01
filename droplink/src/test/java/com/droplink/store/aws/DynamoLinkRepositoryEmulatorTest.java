package com.droplink.store.aws;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: runs against DynamoDB Local (or any DynamoDB endpoint) when TEST_DYNAMO_ENDPOINT is set, for example
 * http://localhost:18000. Fake credentials must be in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY. Creates its own
 * table droplink_test_&lt;random&gt; and deletes it at the end. See docs/EMULATOR-TESTS.md.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DYNAMO_ENDPOINT", matches = ".+")
class DynamoLinkRepositoryEmulatorTest {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static DynamoDbClient dynamo;
    private static String table;
    private static DynamoLinkRepository repo;

    @BeforeAll
    static void createTable() {
        dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create(System.getenv("TEST_DYNAMO_ENDPOINT")))
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-west-2")))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        table = "droplink_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        dynamo.createTable(CreateTableRequest.builder()
                .tableName(table)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
        repo = new DynamoLinkRepository(dynamo, table);
    }

    @AfterAll
    static void deleteTable() {
        if (dynamo != null) {
            try {
                if (table != null) {
                    dynamo.deleteTable(DeleteTableRequest.builder().tableName(table).build());
                }
            } finally {
                dynamo.close();
            }
        }
    }

    private static String newId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static Link newLink(String id, Integer maxDownloads, String passwordHash, Instant expiresAt) {
        return new Link(id, "File \"one\" ü.txt", "text/plain", 10L, "files/" + id + "/File.txt",
                Instant.now().truncatedTo(ChronoUnit.MILLIS), expiresAt, maxDownloads, 0L, passwordHash,
                "a".repeat(64), LinkStatus.PENDING);
    }

    private static Instant inOneHour() {
        return Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    @Test
    void fullLifecycle() {
        String id = newId();
        Link link = newLink(id, 2, "$2a$10$hash", inOneHour());

        repo.create(link);
        assertThat(repo.find(id)).contains(link);

        // PENDING: nothing can be counted yet
        assertThat(repo.tryCountDownload(id, Instant.now())).isEmpty();

        assertThat(repo.activate(id, 4321L, "image/png")).isTrue();
        assertThat(repo.activate(id, 1L, "x/y")).as("second activation").isFalse();
        Link active = repo.find(id).orElseThrow();
        assertThat(active.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(active.size()).isEqualTo(4321L);
        assertThat(active.contentType()).isEqualTo("image/png");
        assertThat(active.downloads()).isZero();
        assertThat(active.maxDownloads()).isEqualTo(2);
        assertThat(active.passwordHash()).isEqualTo("$2a$10$hash");

        assertThat(repo.tryCountDownload(id, Instant.now())).contains(1L);
        assertThat(repo.tryCountDownload(id, Instant.now())).contains(2L);
        assertThat(repo.tryCountDownload(id, Instant.now())).as("limit reached").isEmpty();
        assertThat(repo.find(id).orElseThrow().downloads()).isEqualTo(2L);

        assertThat(repo.delete(id)).isTrue();
        assertThat(repo.find(id)).isEmpty();
        assertThat(repo.delete(id)).isFalse();
    }

    @Test
    void roundTripWithoutMaxDownloadsAndPassword() {
        String id = newId();
        Link link = newLink(id, null, null, inOneHour());
        repo.create(link);

        assertThat(repo.find(id)).contains(link);
        Map<String, AttributeValue> raw = rawMeta(id);
        assertThat(raw).doesNotContainKeys("maxDownloads", "passwordHash");
        assertThat(raw.get("ttl").n()).isEqualTo(String.valueOf(link.expiresAt().getEpochSecond() + 86400));
        assertThat(raw.get("expiresAtEpoch").n()).isEqualTo(String.valueOf(link.expiresAt().getEpochSecond()));

        repo.activate(id, 1, "text/plain");
        for (long i = 1; i <= 30; i++) {
            assertThat(repo.tryCountDownload(id, Instant.now())).contains(i);   // no limit
        }
        repo.delete(id);
    }

    @Test
    void createRefusesAnExistingId() {
        String id = newId();
        repo.create(newLink(id, 1, null, inOneHour()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> repo.create(newLink(id, 9, null, inOneHour())));
        assertThat(repo.find(id).orElseThrow().maxDownloads()).isEqualTo(1);   // the first link was not overwritten
        repo.delete(id);
    }

    @Test
    void unknownLinkBehavesAsMissing() {
        String id = newId();
        assertThat(repo.find(id)).isEmpty();
        assertThat(repo.activate(id, 1, "a/b")).isFalse();
        assertThat(repo.tryCountDownload(id, Instant.now())).isEmpty();
        assertThat(repo.events(id, 10)).isEmpty();
        assertThat(repo.delete(id)).isFalse();
        // the failed update must not have created a ghost item
        assertThat(rawMeta(id)).isEmpty();
    }

    // ---- refusals ----------------------------------------------------------------------------------------------

    @Test
    void tryCountDownloadRefusesWhenExpired() {
        String id = newId();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        repo.create(newLink(id, null, null, expiresAt));
        repo.activate(id, 1, "a/b");

        assertThat(repo.tryCountDownload(id, expiresAt.minusSeconds(1))).contains(1L);
        assertThat(repo.tryCountDownload(id, expiresAt)).as("exactly at expiry").isEmpty();
        assertThat(repo.tryCountDownload(id, expiresAt.plusSeconds(5))).isEmpty();
        assertThat(repo.find(id).orElseThrow().downloads()).isEqualTo(1L);
        repo.delete(id);
    }

    @Test
    void tryCountDownloadRefusesAtTheLimitAndWhenPending() {
        String pending = newId();
        repo.create(newLink(pending, 3, null, inOneHour()));
        assertThat(repo.tryCountDownload(pending, Instant.now())).isEmpty();
        assertThat(repo.find(pending).orElseThrow().downloads()).isZero();

        String oneTime = newId();
        repo.create(newLink(oneTime, 1, null, inOneHour()));
        repo.activate(oneTime, 1, "a/b");
        assertThat(repo.tryCountDownload(oneTime, Instant.now())).contains(1L);
        assertThat(repo.tryCountDownload(oneTime, Instant.now())).isEmpty();
        repo.delete(pending);
        repo.delete(oneTime);
    }

    // ---- concurrency -------------------------------------------------------------------------------------------

    @Test
    void twentyThreadsOnALinkWithFiveDownloadsGiveExactlyFiveSuccesses() throws Exception {
        String id = newId();
        repo.create(newLink(id, 5, null, inOneHour()));
        repo.activate(id, 1, "a/b");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<Long>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return repo.tryCountDownload(id, Instant.now());
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<Long> counts = new ArrayList<>();
        for (Future<Optional<Long>> f : results) {
            f.get(30, TimeUnit.SECONDS).ifPresent(counts::add);
        }
        pool.shutdown();

        assertThat(counts).hasSize(5);
        assertThat(counts).as("every success saw its own number").containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(repo.find(id).orElseThrow().downloads()).isEqualTo(5L);
        repo.delete(id);
    }

    @Test
    void concurrentActivationHasOneWinner() throws Exception {
        String id = newId();
        repo.create(newLink(id, null, null, inOneHour()));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return repo.activate(id, 1, "a/b");
            }));
        }
        go.countDown();
        long winners = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        repo.delete(id);
    }

    // ---- events ------------------------------------------------------------------------------------------------

    @Test
    void eventsAreNewestFirstAndLimited() {
        String id = newId();
        repo.create(newLink(id, null, null, inOneHour()));
        Instant base = Instant.parse("2025-03-01T12:00:00Z");
        for (int i = 0; i < 12; i++) {
            repo.addEvent(id, new DownloadEvent(base.plusMillis(i * 10L), "203.0.113." + "x", "Agent " + i));
        }
        // same millisecond twice: both must be stored (random suffix)
        repo.addEvent(id, new DownloadEvent(base.plusMillis(500), "10.0.0.x", "same ms A"));
        repo.addEvent(id, new DownloadEvent(base.plusMillis(500), "10.0.0.x", "same ms B"));

        List<DownloadEvent> top = repo.events(id, 5);
        assertThat(top).hasSize(5);
        assertThat(top.get(0).at()).isEqualTo(base.plusMillis(500));
        assertThat(top.get(1).at()).isEqualTo(base.plusMillis(500));
        assertThat(top.get(2)).isEqualTo(new DownloadEvent(base.plusMillis(110), "203.0.113.x", "Agent 11"));
        assertThat(top.get(3).userAgent()).isEqualTo("Agent 10");
        assertThat(top.get(4).userAgent()).isEqualTo("Agent 9");

        List<DownloadEvent> all = repo.events(id, 100);
        assertThat(all).hasSize(14);
        assertThat(all).extracting(DownloadEvent::at).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        repo.delete(id);
    }

    @Test
    void eventCopiesTheTtlOfTheLink() {
        String id = newId();
        Link link = newLink(id, null, null, inOneHour());
        repo.create(link);
        repo.addEvent(id, new DownloadEvent(Instant.now(), "1.2.3.x", "UA"));

        Map<String, AttributeValue> event = queryEventItems(id).get(0);
        assertThat(event.get("ttl").n()).isEqualTo(String.valueOf(link.expiresAt().getEpochSecond() + 86400));
        assertThat(event.get("sk").s()).matches("EVT#\\d{13}#[0-9a-f]{4}");
        repo.delete(id);
    }

    @Test
    void eventForAMissingLinkIsIgnored() {
        String id = newId();
        repo.addEvent(id, new DownloadEvent(Instant.now(), "1.2.3.x", "UA"));
        assertThat(queryEventItems(id)).isEmpty();
        assertThat(repo.events(id, 10)).isEmpty();
    }

    @Test
    void deleteRemovesTheLinkAndAllItsEventsAndNothingElse() {
        String id = newId();
        String other = newId();
        repo.create(newLink(id, null, null, inOneHour()));
        repo.create(newLink(other, null, null, inOneHour()));
        for (int i = 0; i < 60; i++) {   // more than two batches of 25
            repo.addEvent(id, new DownloadEvent(Instant.parse("2025-03-01T12:00:00Z").plusMillis(i), "1.2.3.x", "UA"));
        }
        repo.addEvent(other, new DownloadEvent(Instant.now(), "9.9.9.x", "other"));
        assertThat(queryEventItems(id)).hasSize(60);

        assertThat(repo.delete(id)).isTrue();

        assertThat(repo.find(id)).isEmpty();
        assertThat(queryEventItems(id)).isEmpty();
        assertThat(repo.events(id, 100)).isEmpty();
        assertThat(repo.find(other)).isPresent();
        assertThat(repo.events(other, 10)).hasSize(1);
        repo.delete(other);
    }

    // ---- raw helpers -------------------------------------------------------------------------------------------

    private static Map<String, AttributeValue> rawMeta(String id) {
        var response = dynamo.getItem(GetItemRequest.builder().tableName(table).consistentRead(true)
                .key(Map.of("pk", AttributeValue.builder().s("LINK#" + id).build(),
                        "sk", AttributeValue.builder().s("META").build())).build());
        return response.hasItem() ? response.item() : Map.of();
    }

    private static List<Map<String, AttributeValue>> queryEventItems(String id) {
        return dynamo.query(QueryRequest.builder().tableName(table).consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :e)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("LINK#" + id).build(),
                        ":e", AttributeValue.builder().s("EVT#").build()))
                .build()).items();
    }
}
