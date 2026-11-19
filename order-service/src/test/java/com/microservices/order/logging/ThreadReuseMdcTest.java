package com.microservices.order.logging;

import com.microservices.order.support.JwtTestSupport;
import com.microservices.order.support.LogCapture;
import com.microservices.order.support.LogCapture.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Review Focus 2: MDC leaks with thread reuse. A real Tomcat on a random port with ONE worker thread handles a
 * request of a logged-in user and then an anonymous request: the second request must not see the first user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"})
@ActiveProfiles("test")
@AutoConfigureObservability
class ThreadReuseMdcTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private LogCapture logs;

    @BeforeEach
    void startCapture() {
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
    }

    private HttpResponse<String> get(String path, String token, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSecondRequestOnTheSameWorkerThread_showsNothingOfTheFirstUser() throws Exception {
        String token = JwtTestSupport.userToken(777);
        HttpResponse<String> first = get("/api/orders", token, "203.0.113.50");
        HttpResponse<String> second = get("/api/orders", null, null);
        HttpResponse<String> third = get("/api/orders", JwtTestSupport.adminToken(888), "203.0.113.51");
        HttpResponse<String> fourth = get("/api/orders", null, null);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(401);
        assertThat(third.statusCode()).isEqualTo(200);
        assertThat(fourth.statusCode()).isEqualTo(401);

        // The answer reaches the client before the filter has written its access line, so wait for the last one.
        await().atMost(Duration.ofSeconds(10)).until(() -> logs.accessLines("GET", "/api/orders").size() == 4);
        List<Line> lines = logs.accessLines("GET", "/api/orders");
        assertThat(lines).hasSize(4);
        // The test is only meaningful if one thread really served all four requests.
        assertThat(lines.stream().map(Line::thread).distinct()).as("worker threads").hasSize(1);

        assertThat(lines.get(0).mdc("userId")).isEqualTo("777");
        assertThat(lines.get(0).mdc("clientIp")).isEqualTo("203.0.113.50");
        assertThat(lines.get(1).mdc("userId")).isEqualTo("anonymous");
        assertThat(lines.get(1).mdc()).doesNotContainKeys("userEmail", "userRole");
        assertThat(lines.get(1).mdc("clientIp")).isEqualTo("127.0.0.1").isNotEqualTo("203.0.113.50");
        assertThat(lines.get(2).mdc("userId")).isEqualTo("888");
        assertThat(lines.get(2).mdc("userRole")).isEqualTo("ADMIN");
        assertThat(lines.get(3).mdc("userId")).isEqualTo("anonymous");
        assertThat(lines.get(3).mdc()).doesNotContainKeys("userEmail", "userRole");
        assertThat(lines.get(3).mdc("clientIp")).isNotEqualTo("203.0.113.51");

        // Every request has its own trace, and the header is real over HTTP too.
        List<String> traceIds = lines.stream().map(line -> line.mdc("traceId")).toList();
        assertThat(traceIds).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(first.headers().firstValue("X-Trace-Id")).hasValue(traceIds.get(0));
        assertThat(second.headers().firstValue("X-Trace-Id")).hasValue(traceIds.get(1));
    }

    @Test
    void aLogLineWrittenByTheThreadBetweenRequests_hasNoStaleFields() throws Exception {
        get("/api/orders", JwtTestSupport.userToken(777), "203.0.113.50");
        get("/actuator/health", null, null);

        await().atMost(Duration.ofSeconds(10)).until(() -> logs.accessLines("GET", "/actuator/health").size() == 1);
        Line health = logs.accessLine("GET", "/actuator/health");
        assertThat(health.mdc("userId")).isEqualTo("anonymous");
        assertThat(health.mdc()).doesNotContainKeys("userEmail", "userRole");
        assertThat(health.mdc("clientIp")).isNotEqualTo("203.0.113.50");
    }
}
