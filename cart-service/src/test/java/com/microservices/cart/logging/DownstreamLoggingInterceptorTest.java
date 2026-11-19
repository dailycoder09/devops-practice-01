package com.microservices.cart.logging;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.config.RestClientConfig;
import com.microservices.cart.support.FakeProductService;
import com.microservices.cart.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The line that is written for every call to another service, against a fake product service over real HTTP.
 */
class DownstreamLoggingInterceptorTest {

    private static final String LOGGER = DownstreamLoggingInterceptor.class.getName();

    private FakeProductService fake;
    private LogCapture logs;

    @BeforeEach
    void start() {
        fake = new FakeProductService();
        logs = LogCapture.start().level(LOGGER, Level.DEBUG);
    }

    @AfterEach
    void stop() {
        logs.close();
        fake.stop();
    }

    private RestClient client(String baseUrl, int readTimeoutMs) {
        return RestClientConfig.createRestClient(baseUrl, 500, readTimeoutMs);
    }

    private JsonNode theCallLine() {
        return logs.single("downstream_call");
    }

    @Test
    void aGoodAnswer_isWrittenAtDebug_withTheContractFields() {
        fake.addProduct("PHONE-1", "Test Phone", "9.99");

        client(fake.baseUrl(), 2000).get().uri("/api/products/sku/{sku}", "PHONE-1").retrieve().toBodilessEntity();

        JsonNode line = theCallLine();
        assertThat(line.get("level").asText()).isEqualTo("DEBUG");
        assertThat(line.get("downstreamService").asText()).isEqualTo("product-service");
        assertThat(line.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(line.get("httpPath").asText()).isEqualTo("/api/products/sku/PHONE-1");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(line.get("message").asText()).matches("Call to product-service: GET /api/products/sku/PHONE-1 -> 200 in \\d+ ms");
    }

    @Test
    void aGoodAnswer_isNotWrittenAtInfo() {
        logs.level(LOGGER, Level.INFO);
        fake.addProduct("PHONE-1", "Test Phone", "9.99");

        client(fake.baseUrl(), 2000).get().uri("/api/products/sku/{sku}", "PHONE-1").retrieve().toBodilessEntity();

        assertThat(logs.events("downstream_call")).isEmpty();
    }

    @Test
    void a404_isWrittenAtWarn_andTheCallerStillGetsItsException() {
        assertThatThrownBy(() -> client(fake.baseUrl(), 2000).get().uri("/api/products/sku/NOPE").retrieve()
                .toBodilessEntity()).isInstanceOf(HttpClientErrorException.NotFound.class);

        JsonNode line = theCallLine();
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(404);
    }

    @Test
    void a500_isWrittenAtWarn() {
        fake.failWithStatus(500);

        assertThatThrownBy(() -> client(fake.baseUrl(), 2000).get().uri("/api/products/sku/X").retrieve()
                .toBodilessEntity()).isInstanceOf(HttpServerErrorException.class);

        JsonNode line = theCallLine();
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(500);
    }

    @Test
    void aTimeOut_isWrittenAtWarn_withTheExceptionName_andTheExceptionIsThrownAgain() {
        fake.delayMillis(1500);

        assertThatThrownBy(() -> client(fake.baseUrl(), 200).get().uri("/api/products/sku/SLOW").retrieve()
                .toBodilessEntity()).isInstanceOf(ResourceAccessException.class);

        JsonNode line = theCallLine();
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("downstreamService").asText()).isEqualTo("product-service");
        assertThat(line.get("httpPath").asText()).isEqualTo("/api/products/sku/SLOW");
        assertThat(line.get("exception").asText()).isNotBlank().contains("Exception");
        assertThat(line.has("httpStatus")).isFalse();
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(150);
    }

    @Test
    void aRefusedConnection_isWrittenAtWarn_andTheExceptionIsThrownAgain() {
        String deadUrl = fake.baseUrl();
        fake.stop();

        assertThatThrownBy(() -> client(deadUrl, 500).get().uri("/api/products/sku/X").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);

        JsonNode line = theCallLine();
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("exception").asText()).isNotBlank();
    }

    @Test
    void theQuery_theHeadersAndTheBody_areNeverLogged() {
        fake.addProduct("PHONE-1", "Test Phone", "9.99");

        client(fake.baseUrl(), 2000).get().uri("/api/products/sku/PHONE-1?token=QUERYSECRET&q=a@b.c")
                .header(HttpHeaders.AUTHORIZATION, "Bearer HEADERSECRET")
                .header("X-Other", "OTHERSECRET").retrieve().toBodilessEntity();

        assertThat(theCallLine().get("httpPath").asText()).isEqualTo("/api/products/sku/PHONE-1");
        assertThat(logs.allText()).doesNotContain("QUERYSECRET").doesNotContain("HEADERSECRET")
                .doesNotContain("OTHERSECRET").doesNotContain("Test Phone").doesNotContain("a@b.c");
    }

    @Test
    void aPathWithControlCharacters_isCleaned() {
        fake.addProduct("X", "X", "1");
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/api/a%0D%0Ab"));
        DownstreamLoggingInterceptor interceptor = new DownstreamLoggingInterceptor("product-service");

        try {
            interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThat(theCallLine().get("httpPath").asText()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void aProblemWhileWritingTheLine_neverChangesTheAnswer() throws IOException {
        ClientHttpResponse broken = mock(ClientHttpResponse.class);
        when(broken.getStatusCode()).thenThrow(new IOException("cannot read the status"));
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/x"));
        ClientHttpRequestExecution execution = (req, body) -> broken;

        ClientHttpResponse answer = new DownstreamLoggingInterceptor("product-service")
                .intercept(request, new byte[0], execution);

        assertThat(answer).isSameAs(broken);
        assertThat(logs.events("downstream_call")).isEmpty();
    }

    @Test
    void anExceptionFromTheCall_isNotReplaced() {
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/x"));
        IllegalStateException original = new IllegalStateException("original");

        assertThatThrownBy(() -> new DownstreamLoggingInterceptor("product-service")
                .intercept(request, new byte[0], (req, body) -> {
                    throw original;
                })).isSameAs(original);
        assertThat(theCallLine().get("level").asText()).isEqualTo("WARN");
    }
}
