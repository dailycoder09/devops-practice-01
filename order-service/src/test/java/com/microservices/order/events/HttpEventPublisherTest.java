package com.microservices.order.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The publisher talks to a real local HTTP server: what it sends, that it never blocks and that it never throws. */
class HttpEventPublisherTest {

    private record Received(String method, String secret, String contentType, String body) { }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger(200);
    private volatile long delayMillis = 0;
    private volatile CountDownLatch arrived = new CountDownLatch(1);
    private HttpServer server;
    private String url;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("X-Event-Secret"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
            arrived.countDown();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        MDC.clear();
        server.stop(0);
    }

    private boolean waitForEvent() throws InterruptedException {
        return arrived.await(5, TimeUnit.SECONDS);
    }

    @Test
    void publish_sendsAPostWithTheSecretAndTheEventAsJson() throws Exception {
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", url, "the-shared-secret");

        publisher.publish("USER_REGISTERED", Map.of("userId", 42, "email", "ann@example.com"));

        assertThat(waitForEvent()).isTrue();
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.secret()).isEqualTo("the-shared-secret");
        assertThat(request.contentType()).startsWith("application/json");
        JsonNode event = json.readTree(request.body());
        assertThat(event.get("type").asText()).isEqualTo("USER_REGISTERED");
        assertThat(event.get("source").asText()).isEqualTo("order-service");
        assertThat(event.get("occurredAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(event.get("data").get("userId").asInt()).isEqualTo(42);
        assertThat(event.get("data").get("email").asText()).isEqualTo("ann@example.com");
        publisher.close();
    }

    @Test
    void publish_neverPutsTheSecretIntoTheBody() throws Exception {
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", url, "the-shared-secret");

        publisher.publish("USER_REGISTERED", Map.of("userId", 1));

        assertThat(waitForEvent()).isTrue();
        assertThat(received.get(0).body()).doesNotContain("the-shared-secret");
        publisher.close();
    }

    @Test
    void publish_carriesTheTraceIdOfTheCallerWhenThereIsOne() throws Exception {
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", url, "s");
        MDC.put("traceId", "abc123trace");

        publisher.publish("USER_REGISTERED", Map.of("userId", 1));

        assertThat(waitForEvent()).isTrue();
        assertThat(json.readTree(received.get(0).body()).get("traceId").asText()).isEqualTo("abc123trace");
        publisher.close();
    }

    @Test
    void publish_returnsAtOnce_evenWhenTheServerIsSlow() throws Exception {
        delayMillis = 1500;
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", url, "s");

        long start = System.nanoTime();
        publisher.publish("USER_REGISTERED", Map.of("userId", 1));
        long tookMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMillis).as("the caller must not wait for the Lambda").isLessThan(500);
        assertThat(waitForEvent()).isTrue();
        publisher.close();
    }

    @Test
    void publish_neverThrows_whenTheServerAnswersWithAnError_andTheNextEventStillGoesOut() throws Exception {
        answer.set(500);
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", url, "s");

        assertThatCode(() -> publisher.publish("USER_REGISTERED", Map.of("userId", 1))).doesNotThrowAnyException();
        assertThat(waitForEvent()).isTrue();

        answer.set(200);
        arrived = new CountDownLatch(1);
        publisher.publish("USER_REGISTERED", Map.of("userId", 2));
        assertThat(waitForEvent()).isTrue();
        assertThat(received).hasSize(2);
        publisher.close();
    }

    @Test
    void publish_neverThrows_whenNobodyListens() throws Exception {
        int closedPort = server.getAddress().getPort();
        server.stop(0);
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", "http://127.0.0.1:" + closedPort + "/", "s");

        assertThatCode(() -> publisher.publish("USER_REGISTERED", Map.of("userId", 1))).doesNotThrowAnyException();
        publisher.close();
        // The tearDown stops the server again; stopping twice is harmless.
        startServerAgainForTearDown();
    }

    private void startServerAgainForTearDown() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @Test
    void publish_dropsEventsInsteadOfPilingUp_whenTheQueueIsFull() throws Exception {
        delayMillis = 300;
        HttpEventPublisher publisher = new HttpEventPublisher("order-service", url, "s");

        assertThatCode(() -> {
            for (int i = 0; i < 400; i++) {
                publisher.publish("USER_REGISTERED", Map.of("userId", i));
            }
        }).doesNotThrowAnyException();
        publisher.close();
    }
}
