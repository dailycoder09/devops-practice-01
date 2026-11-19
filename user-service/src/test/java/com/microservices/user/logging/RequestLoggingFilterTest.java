package com.microservices.user.logging;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.user.support.LogCapture;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The access-log filter on its own (no Spring): what it writes, at which level, and that it always cleans up.
 */
class RequestLoggingFilterTest {

    private static final String FILTER_LOGGER = RequestLoggingFilter.class.getName();

    private LogCapture logs;

    @BeforeEach
    void start() {
        MDC.clear();
        // DEBUG is on, so lines written at DEBUG (health checks) are seen too
        logs = LogCapture.start().level(FILTER_LOGGER, Level.DEBUG);
    }

    @AfterEach
    void stop() {
        logs.close();
        MDC.clear();
    }

    private static RequestLoggingFilter filter(long slowMs, Tracer tracer) {
        LoggingProperties properties = new LoggingProperties();
        properties.setSlowRequestMs(slowMs);
        return new RequestLoggingFilter(properties, tracer);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("10.1.2.3");
        return request;
    }

    private JsonNode accessLine() {
        return logs.events("http_request").stream().reduce((first, second) -> {
            throw new AssertionError("More than one access line: " + logs.events("http_request"));
        }).orElseThrow(() -> new AssertionError("No access line was written"));
    }

    private JsonNode run(MockHttpServletRequest request, int status) throws Exception {
        filter(2000, null).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
        });
        return accessLine();
    }

    @Test
    void filter_runsAfterTheTracingFilter_andBeforeSpringSecurity() {
        assertThat(filter(2000, null).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Test
    void accessLine_hasMethodPathStatusAndDuration() throws Exception {
        JsonNode line = run(request("GET", "/api/products"), 200);

        assertThat(line.get("message").asText()).matches("HTTP GET /api/products -> 200 in \\d+ ms");
        assertThat(line.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(line.get("httpPath").asText()).isEqualTo("/api/products");
        assertThat(line.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(line.has("slow")).isFalse();
    }

    @Test
    void accessLine_hasTheClientIp_andAnonymousUser_whenNobodySetOne() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/x");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        JsonNode line = run(request, 200);

        assertThat(line.get("clientIp").asText()).isEqualTo("203.0.113.9");
        assertThat(line.get("userId").asText()).isEqualTo("anonymous");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(line.has("userRole")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"200,INFO", "201,INFO", "204,INFO", "302,INFO", "400,INFO", "404,INFO", "409,INFO", "422,INFO",
            "401,WARN", "403,WARN", "500,ERROR", "503,ERROR"})
    void level_followsTheStatus(int status, String level) throws Exception {
        JsonNode line = run(request("GET", "/api/x"), status);

        assertThat(line.get("level").asText()).isEqualTo(level);
        assertThat(line.get("httpStatus").asInt()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/healthz",
            "/actuator/prometheus"})
    void healthChecksAndMetricScrapes_areWrittenAtDebug(String path) throws Exception {
        JsonNode line = run(request("GET", path), 200);

        assertThat(line.get("level").asText()).isEqualTo("DEBUG");
    }

    @Test
    void aFailingHealthCheck_isNotHidden() throws Exception {
        JsonNode line = run(request("GET", "/actuator/health"), 503);

        assertThat(line.get("level").asText()).isEqualTo("ERROR");
    }

    @Test
    void otherActuatorCalls_areInfo() throws Exception {
        assertThat(run(request("GET", "/actuator/info"), 200).get("level").asText()).isEqualTo("INFO");
    }

    @Test
    void aSlowRequest_isWarn_withSlowTrue() throws Exception {
        filter(20, null).doFilter(request("GET", "/api/slow"), new MockHttpServletResponse(), (req, res) -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        JsonNode line = accessLine();
        assertThat(line.get("level").asText()).isEqualTo("WARN");
        assertThat(line.get("slow").asBoolean()).isTrue();
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void aFastRequest_isNotSlow() throws Exception {
        JsonNode line = run(request("GET", "/api/fast"), 200);

        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(line.has("slow")).isFalse();
    }

    @Test
    void theQueryString_isNeverLogged() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/users");
        request.setQueryString("q=secret-person@example.com&token=SECRET123");

        JsonNode line = run(request, 200);

        assertThat(line.get("httpPath").asText()).isEqualTo("/api/users");
        assertThat(logs.allText()).doesNotContain("SECRET123").doesNotContain("secret-person");
    }

    @Test
    void thePath_isCleaned_soNoSecondLineOrFieldCanBeMade() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/a\r\n{\"level\":\"ERROR\"}\u0000");

        JsonNode line = run(request, 200);

        assertThat(line.get("httpPath").asText()).isEqualTo("/api/a__{\"level\":\"ERROR\"}_");
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(logs.all()).hasSize(1);
    }

    @Test
    void aVeryLongPath_isCutTo200Characters() throws Exception {
        JsonNode line = run(request("GET", "/" + "p".repeat(5000)), 200);

        assertThat(line.get("httpPath").asText()).hasSize(200);
    }

    @Test
    void traceIdHeader_isSetFromTheCurrentTrace() throws Exception {
        Tracer tracer = tracerWithTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(2000, tracer).doFilter(request("GET", "/api/x"), response, (req, res) -> { });

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void traceIdHeader_isSetBeforeTheChainRuns() throws Exception {
        Tracer tracer = tracerWithTraceId("abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter(2000, tracer).doFilter(request("GET", "/api/x"), response,
                (req, res) -> seenInChain.set(((jakarta.servlet.http.HttpServletResponse) res).getHeader("X-Trace-Id")));

        assertThat(seenInChain.get()).isEqualTo("abc123");
    }

    @Test
    void withoutATrace_noTraceIdHeaderIsSet() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(2000, null).doFilter(request("GET", "/api/x"), response, (req, res) -> { });
        Tracer noSpan = mock(Tracer.class);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter(2000, noSpan).doFilter(request("GET", "/api/x"), second, (req, res) -> { });

        assertThat(response.getHeader("X-Trace-Id")).isNull();
        assertThat(second.getHeader("X-Trace-Id")).isNull();
    }

    @Test
    void mdc_hasTheClientIpAndAnonymousUser_whileTheChainRuns_andIsEmptyAfterwards() throws Exception {
        AtomicReference<String> clientIp = new AtomicReference<>();
        AtomicReference<String> userId = new AtomicReference<>();

        filter(2000, null).doFilter(request("GET", "/api/x"), new MockHttpServletResponse(), (req, res) -> {
            clientIp.set(MDC.get("clientIp"));
            userId.set(MDC.get("userId"));
        });

        assertThat(clientIp.get()).isEqualTo("10.1.2.3");
        assertThat(userId.get()).isEqualTo("anonymous");
        assertNoMdcKeysLeft();
    }

    @Test
    void mdc_keysSetByTheUserFilter_areRemovedToo() throws Exception {
        filter(2000, null).doFilter(request("GET", "/api/x"), new MockHttpServletResponse(), (req, res) -> {
            MDC.put("userId", "7");
            MDC.put("userEmail", "a@example.com");
            MDC.put("userRole", "USER");
        });

        assertNoMdcKeysLeft();
    }

    @Test
    void accessLine_carriesTheUserFieldsSetInsideTheChain() throws Exception {
        filter(2000, null).doFilter(request("GET", "/api/x"), new MockHttpServletResponse(), (req, res) -> {
            MDC.put("userId", "7");
            MDC.put("userEmail", "a@example.com");
            MDC.put("userRole", "ADMIN");
        });

        JsonNode line = accessLine();
        assertThat(line.get("userId").asText()).isEqualTo("7");
        assertThat(line.get("userEmail").asText()).isEqualTo("a@example.com");
        assertThat(line.get("userRole").asText()).isEqualTo("ADMIN");
    }

    @Test
    void aRequestThatThrows_isLoggedAs500_theExceptionPassesOn_andMdcIsClean() {
        MockHttpServletRequest request = request("GET", "/api/x");
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("broken");
        };

        assertThatThrownBy(() -> filter(2000, null).doFilter(request, new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class).hasMessage("broken");

        JsonNode line = accessLine();
        assertThat(line.get("httpStatus").asInt()).isEqualTo(500);
        assertThat(line.get("level").asText()).isEqualTo("ERROR");
        assertNoMdcKeysLeft();
    }

    @Test
    void checkedExceptionsOfTheChain_passOnUnchanged() {
        FilterChain chain = (req, res) -> {
            throw new ServletException("servlet trouble");
        };
        assertThatThrownBy(() -> filter(2000, null).doFilter(request("GET", "/x"), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);

        FilterChain ioChain = (req, res) -> {
            throw new IOException("io trouble");
        };
        assertThatThrownBy(() -> filter(2000, null).doFilter(request("GET", "/x"), new MockHttpServletResponse(), ioChain))
                .isInstanceOf(IOException.class);
        assertNoMdcKeysLeft();
    }

    @Test
    void aProblemInsideTheLoggingCode_neverBreaksTheRequest() throws Exception {
        Tracer broken = mock(Tracer.class);
        when(broken.currentSpan()).thenThrow(new IllegalStateException("tracer is broken"));
        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(2000, broken).doFilter(request("GET", "/api/x"), response, (req, res) -> {
            chainCalls.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
        });

        assertThat(chainCalls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(accessLine().get("httpStatus").asInt()).isEqualTo(201);
        assertNoMdcKeysLeft();
    }

    @Test
    void anErrorDispatch_isNotLoggedAgain() throws Exception {
        MockHttpServletRequest request = request("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        // the container sets this attribute when it forwards a failed request to the error page
        request.setAttribute("jakarta.servlet.error.request_uri", "/api/x");
        AtomicInteger chainCalls = new AtomicInteger();

        filter(2000, null).doFilter(request, new MockHttpServletResponse(), (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls).hasValue(1);
        assertThat(logs.events("http_request")).isEmpty();
    }

    @Test
    void aStaleMdcFromAnEarlierUseOfTheThread_isNotCarriedIntoTheLine() throws Exception {
        MDC.put("userId", "stale-user");
        MDC.put("userEmail", "stale@example.com");
        MDC.put("userRole", "ADMIN");

        JsonNode line = run(request("GET", "/api/x"), 200);

        assertThat(line.get("userId").asText()).isEqualTo("anonymous");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(line.has("userRole")).isFalse();
    }

    private static Tracer tracerWithTraceId(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        return tracer;
    }

    private static void assertNoMdcKeysLeft() {
        for (String key : new String[] {"clientIp", "userId", "userEmail", "userRole", "traceId", "spanId"}) {
            assertThat(MDC.get(key)).as("MDC key " + key).isNull();
        }
    }
}
