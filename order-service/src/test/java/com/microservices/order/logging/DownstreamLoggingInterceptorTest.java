package com.microservices.order.logging;

import com.microservices.order.support.LogCapture;
import com.microservices.order.support.LogCapture.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The log line for every call to another service (event downstream_call): service name, method, path without query,
 * status, duration. Never a header, a body or a query string. Logging never changes the outcome of the call.
 */
class DownstreamLoggingInterceptorTest {

    private static final byte[] BODY = "secret-request-body".getBytes(StandardCharsets.UTF_8);

    private LogCapture logs;
    private final DownstreamLoggingInterceptor interceptor = new DownstreamLoggingInterceptor("inventory-service");

    @BeforeEach
    void startCapture() {
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
    }

    private static MockClientHttpRequest request(String uri) {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create(uri));
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer SECRET-TOKEN-VALUE");
        return request;
    }

    private static ClientHttpRequestExecution answering(int status) {
        return (request, body) -> new MockClientHttpResponse("secret-response-body".getBytes(StandardCharsets.UTF_8),
                HttpStatusCode.valueOf(status));
    }

    private Line theLine() {
        return logs.single("downstream_call");
    }

    @Test
    void aSuccessfulCall_isLoggedAtDebug_withTheContractFields() throws Exception {
        MockClientHttpRequest request = request("http://inventory:8082/api/inventory/sku/SKU-A/reserve?quantity=2&orderId=9");

        ClientHttpResponse response = interceptor.intercept(request, BODY, answering(200));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Line line = theLine();
        assertThat(line.level()).isEqualTo("DEBUG");
        assertThat(line.field("downstreamService")).isEqualTo("inventory-service");
        assertThat(line.field("httpMethod")).isEqualTo("POST");
        assertThat(line.field("httpPath")).isEqualTo("/api/inventory/sku/SKU-A/reserve");
        assertThat(line.field("httpStatus")).isEqualTo(200);
        assertThat(((Number) line.field("durationMs")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(line.field("exception")).isNull();
        assertThat(line.message()).contains("inventory-service").contains("200");
    }

    @ParameterizedTest(name = "status {0} is logged at {1}")
    @CsvSource({"200,DEBUG", "201,DEBUG", "204,DEBUG", "299,DEBUG", "400,WARN", "401,WARN", "403,WARN", "404,WARN",
            "409,WARN", "500,WARN", "503,WARN"})
    void level_followsTheStatus(int status, String level) throws Exception {
        interceptor.intercept(request("http://inventory:8082/api/x"), BODY, answering(status));

        assertThat(theLine().level()).isEqualTo(level);
        assertThat(theLine().field("httpStatus")).isEqualTo(status);
    }

    @Test
    void aTimeOut_isLoggedAtWarn_withTheExceptionClass_andRethrown() {
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        ClientHttpRequestExecution failing = (request, body) -> {
            throw timeout;
        };

        assertThatThrownBy(() -> interceptor.intercept(request("http://inventory:8082/api/x"), BODY, failing))
                .isSameAs(timeout);

        Line line = theLine();
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.field("exception")).isEqualTo("SocketTimeoutException");
        assertThat(line.field("httpPath")).isEqualTo("/api/x");
        assertThat(line.field("httpStatus")).isNull();
        assertThat(line.stackTrace()).as("an expected network failure needs no stack trace").isNull();
    }

    @Test
    void aConnectionFailure_isLoggedAtWarn_andRethrown() {
        IOException refused = new java.net.ConnectException("Connection refused");

        assertThatThrownBy(() -> interceptor.intercept(request("http://inventory:8082/api/x"), BODY,
                (request, body) -> {
                    throw refused;
                })).isSameAs(refused);

        assertThat(theLine().field("exception")).isEqualTo("ConnectException");
        assertThat(theLine().level()).isEqualTo("WARN");
    }

    @Test
    void aRuntimeException_isLoggedAtWarn_andRethrownUnchanged() {
        IllegalStateException failure = new IllegalStateException("bug");

        assertThatThrownBy(() -> interceptor.intercept(request("http://inventory:8082/api/x"), BODY,
                (request, body) -> {
                    throw failure;
                })).isSameAs(failure);

        assertThat(theLine().field("exception")).isEqualTo("IllegalStateException");
    }

    @Test
    void headersBodiesAndQueryStrings_areNeverLogged() throws Exception {
        MockClientHttpRequest request = request("http://user:password@inventory:8082/api/x?token=QUERY-SECRET&q=1");

        interceptor.intercept(request, BODY, answering(409));

        String everything = logs.allText();
        assertThat(everything).doesNotContain("SECRET-TOKEN-VALUE").doesNotContain("Bearer")
                .doesNotContain("secret-request-body").doesNotContain("secret-response-body")
                .doesNotContain("QUERY-SECRET").doesNotContain("token=").doesNotContain("password")
                .doesNotContain("user:").doesNotContain("Authorization");
    }

    @Test
    void thePath_isCleaned_noControlCharactersAndAtMost200Characters() throws Exception {
        interceptor.intercept(request("http://inventory:8082/api/inventory/sku/A%0D%0AINFO%20fake/reserve"), BODY,
                answering(200));
        interceptor.intercept(request("http://inventory:8082/api/" + "z".repeat(3000)), BODY, answering(200));

        Line first = logs.events("downstream_call").get(0);
        Line second = logs.events("downstream_call").get(1);
        assertThat((String) first.field("httpPath")).doesNotContain("\r").doesNotContain("\n")
                .contains("A__INFO fake");
        assertThat(first.message()).doesNotContain("\r").doesNotContain("\n");
        assertThat((String) second.field("httpPath")).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void theServiceName_comesFromTheConstructor() throws Exception {
        new DownstreamLoggingInterceptor("cart-service")
                .intercept(request("http://cart:8084/api/cart"), BODY, answering(200));

        assertThat(theLine().field("downstreamService")).isEqualTo("cart-service");
    }

    @Test
    void ifTheLineCannotBeWritten_theCallStillReturnsItsResponse() throws Exception {
        MockClientHttpRequest unreadableUri = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/a")) {
            @Override
            public URI getURI() {
                throw new IllegalStateException("no uri");
            }
        };

        ClientHttpResponse response = interceptor.intercept(unreadableUri, BODY, answering(200));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void ifTheStatusCannotBeRead_theCallStillReturnsItsResponse() throws Exception {
        ClientHttpResponse broken = mock(ClientHttpResponse.class);
        when(broken.getStatusCode()).thenThrow(new IOException("cannot read status"));

        ClientHttpResponse response = interceptor.intercept(request("http://x/a"), BODY, (request, body) -> broken);

        assertThat(response).isSameAs(broken);
    }

    @Test
    void ifTheLineCannotBeWrittenForAFailure_theOriginalExceptionIsStillThrown() {
        MockClientHttpRequest unreadableUri = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/a")) {
            @Override
            public URI getURI() {
                throw new IllegalStateException("no uri");
            }
        };
        IOException original = new IOException("network is down");

        assertThatThrownBy(() -> interceptor.intercept(unreadableUri, BODY, (request, body) -> {
            throw original;
        })).isSameAs(original);
    }
}
