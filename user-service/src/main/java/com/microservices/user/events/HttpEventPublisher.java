package com.microservices.user.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends each event as one JSON POST to a web address (an AWS Lambda function URL), with the shared secret in the
 * header X-Event-Secret. The Lambda refuses calls without it.
 *
 * The call is made on a separate thread, so the caller never waits. At most 100 events wait in line; when the
 * line is full (the Lambda has been down for a long time) new events are dropped and logged. A failed call is
 * logged as a warning and forgotten: there is no retry, because the order or the registration is already saved.
 * The secret and the address are never logged.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Slf4j
public class HttpEventPublisher implements EventPublisher, AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final int MAX_WAITING = 100;

    private final String source;
    private final URI url;
    private final String secret;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_WAITING), runnable -> {
                Thread thread = new Thread(runnable, "event-publisher");
                thread.setDaemon(true);
                return thread;
            });

    public HttpEventPublisher(String source, String url, String secret) {
        this.source = source;
        this.url = URI.create(url);
        this.secret = secret;
    }

    @Override
    public void publish(String type, Map<String, Object> data) {
        String body;
        try {
            // Built here, on the thread of the request, so the trace id of the request goes with the event.
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("source", source);
            event.put("occurredAt", Instant.now().toString());
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                event.put("traceId", traceId);
            }
            event.put("data", data);
            body = json.writeValueAsString(event);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Event {} was not sent: it could not be written as JSON ({})", type, e.getClass().getSimpleName());
            return;
        }
        try {
            worker.execute(() -> send(type, body));
        } catch (RejectedExecutionException e) {
            log.warn("Event {} was dropped: too many events are waiting to be sent", type);
        }
    }

    private void send(String type, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Event-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                log.debug("Event {} was sent", type);
            } else {
                log.warn("Event {} was not accepted: the receiver answered {}", type, response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.warn("Event {} was not sent: {}", type, e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
