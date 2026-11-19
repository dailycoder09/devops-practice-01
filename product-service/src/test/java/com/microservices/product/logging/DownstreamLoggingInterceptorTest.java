package com.microservices.product.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DownstreamLoggingInterceptorTest {

    private static final String BASE_URL = "http://inventory.test";

    private final Logger interceptorLogger = (Logger) LoggerFactory.getLogger(DownstreamLoggingInterceptor.class);
    private Level originalLevel;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        originalLevel = interceptorLogger.getLevel();
        interceptorLogger.setLevel(Level.DEBUG);
        capture = LogCapture.start();
    }

    @AfterEach
    void tearDown() {
        capture.close();
        interceptorLogger.setLevel(originalLevel);
    }

    private RestClient.Builder builder() {
        return RestClient.builder().baseUrl(BASE_URL)
                .requestInterceptor(new DownstreamLoggingInterceptor("inventory-service"));
    }

    @Test
    void a2xxAnswer_isLoggedAtDebug_withTheAgreedFields() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/inventory/sku/ABC-1?secret=hunter2")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        builder.build().get().uri("/api/inventory/sku/ABC-1?secret=hunter2").header(HttpHeaders.AUTHORIZATION, "Bearer abc.def.ghi")
                .retrieve().body(String.class);

        var lines = capture.withEvent("downstream_call");
        assertThat(lines).hasSize(1);
        LogCapture.Line line = lines.get(0);
        assertThat(line.level()).isEqualTo("DEBUG");
        assertThat(line.text("downstreamService")).isEqualTo("inventory-service");
        assertThat(line.text("httpMethod")).isEqualTo("GET");
        assertThat(line.text("httpPath")).isEqualTo("/api/inventory/sku/ABC-1");
        assertThat(line.number("httpStatus")).isEqualTo(200);
        assertThat(line.number("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(line.raw()).doesNotContain("hunter2").doesNotContain("abc.def.ghi").doesNotContain("Authorization")
                .doesNotContain("secret");
    }

    @Test
    void a4xxAnswer_isWarn() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/inventory/sku/NOPE")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> builder.build().get().uri("/api/inventory/sku/NOPE").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);

        LogCapture.Line line = capture.withEvent("downstream_call").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.number("httpStatus")).isEqualTo(404);
    }

    @Test
    void a5xxAnswer_isWarn() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/inventory/sku/ABC-1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> builder.build().get().uri("/api/inventory/sku/ABC-1").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);

        LogCapture.Line line = capture.withEvent("downstream_call").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.number("httpStatus")).isEqualTo(502);
    }

    @Test
    void anIoError_isWarn_withTheExceptionClass_andIsRethrown() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/inventory/sku/ABC-1")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out after 3000 ms with secret details");
        });

        assertThatThrownBy(() -> builder.build().get().uri("/api/inventory/sku/ABC-1").retrieve().body(String.class))
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        var lines = capture.withEvent("downstream_call");
        assertThat(lines).hasSize(1);
        LogCapture.Line line = lines.get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.text("exception")).isEqualTo("java.net.SocketTimeoutException");
        assertThat(line.has("httpStatus")).isFalse();
        assertThat(line.text("httpPath")).isEqualTo("/api/inventory/sku/ABC-1");
        assertThat(line.has("stack_trace")).isFalse();
    }

    @Test
    void aPathWithLineBreaks_isCleaned() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/inventory/sku/A%0D%0AB")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        builder.build().get().uri("/api/inventory/sku/{sku}", "A\r\nB").retrieve().body(String.class);

        LogCapture.Line line = capture.withEvent("downstream_call").get(0);
        assertThat(line.raw().stripTrailing()).doesNotContain("\n").doesNotContain("\r");
        assertThat(line.message()).doesNotContain("\n");
    }

    @Test
    void aFailureInTheLoggingCode_neverBreaksTheCall() throws IOException {
        DownstreamLoggingInterceptor interceptor = new DownstreamLoggingInterceptor("inventory-service");
        HttpRequest brokenRequest = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                throw new IllegalStateException("method trouble");
            }

            @Override
            public URI getURI() {
                throw new IllegalStateException("uri trouble");
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        ClientHttpResponse answer = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        ClientHttpRequestExecution execution = (request, body) -> answer;

        ClientHttpResponse result = interceptor.intercept(brokenRequest, new byte[0], execution);

        assertThat(result).isSameAs(answer);
    }

    @Test
    void aFailureInTheLoggingCode_neverHidesTheRealIoError() {
        DownstreamLoggingInterceptor interceptor = new DownstreamLoggingInterceptor("inventory-service");
        HttpRequest brokenRequest = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                throw new IllegalStateException("method trouble");
            }

            @Override
            public URI getURI() {
                throw new IllegalStateException("uri trouble");
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        ClientHttpRequestExecution execution = (request, body) -> {
            throw new IOException("connection refused");
        };

        assertThatThrownBy(() -> interceptor.intercept(brokenRequest, new byte[0], execution))
                .isInstanceOf(IOException.class).hasMessage("connection refused");
    }

    @Test
    void theQuietVariant_logsEverythingAtDebug() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL)
                .requestInterceptor(new DownstreamLoggingInterceptor("ec2-metadata", true));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/latest/meta-data/public-ipv4")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> builder.build().get().uri("/latest/meta-data/public-ipv4").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);

        LogCapture.Line line = capture.withEvent("downstream_call").get(0);
        assertThat(line.level()).isEqualTo("DEBUG");
        assertThat(line.number("httpStatus")).isEqualTo(404);
    }
}
