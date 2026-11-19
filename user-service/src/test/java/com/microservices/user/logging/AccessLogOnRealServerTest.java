package com.microservices.user.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Over real HTTP, with exactly one request thread: the MDC of one request must not show up in the next one
 * that runs on the same thread, and the trace id header must match the logged one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"})
@AutoConfigureObservability
@ActiveProfiles("test")
class AccessLogOnRealServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();
    private LogCapture logs;

    @BeforeEach
    void start() {
        logs = LogCapture.start();
    }

    @AfterEach
    void stop() {
        logs.close();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder url(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private JsonNode accessLineFor(String path) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !logs.accessLines(path).isEmpty());
        return logs.singleAccessLine(path);
    }

    @Test
    void theUserOfOneRequest_neverLeaksIntoTheNextRequestOnTheSameThread() throws Exception {
        String email = "real-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}";
        send(url("/api/users/register").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        String token = objectMapper.readTree(send(url("/api/users/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))).body()).get("token").asText();

        HttpResponse<String> first = send(url("/api/users/me").header("Authorization", "Bearer " + token).GET());
        JsonNode firstLine = accessLineFor("/api/users/me");
        HttpResponse<String> second = send(url("/actuator/info").header("X-Forwarded-For", "203.0.113.50").GET());
        JsonNode secondLine = accessLineFor("/actuator/info");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(firstLine.get("userEmail").asText()).isEqualTo(email);
        assertThat(secondLine.get("userId").asText()).isEqualTo("anonymous");
        assertThat(secondLine.has("userEmail")).isFalse();
        assertThat(secondLine.has("userRole")).isFalse();
        assertThat(secondLine.get("clientIp").asText()).isEqualTo("203.0.113.50");
        assertThat(secondLine.get("thread_name").asText()).isEqualTo(firstLine.get("thread_name").asText());
        assertThat(secondLine.get("traceId").asText()).isNotEqualTo(firstLine.get("traceId").asText());
    }

    @Test
    void theTraceIdHeader_equalsTheLoggedTraceId_overRealHttp() throws Exception {
        HttpResponse<String> response = send(url("/actuator/info").GET());

        JsonNode line = accessLineFor("/actuator/info");
        assertThat(response.headers().firstValue("X-Trace-Id")).hasValue(line.get("traceId").asText());
        assertThat(line.get("clientIp").asText()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }
}
