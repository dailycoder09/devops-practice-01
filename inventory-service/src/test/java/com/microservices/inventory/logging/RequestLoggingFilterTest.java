package com.microservices.inventory.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filter on its own (no Spring context): levels, the line it writes, MDC clean-up, and that
 * a problem inside the logging code can never change the answer of a request.
 */
class RequestLoggingFilterTest {

    private static final String[] OUR_KEYS = {"clientIp", "userId", "userEmail", "userRole"};

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private Level originalLevel;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
        capture = LogCapture.start();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        capture.close();
        filterLogger.setLevel(originalLevel);
        MDC.clear();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.10");
        return request;
    }

    private LogCapture.Line runAndGetAccessLine(RequestLoggingFilter filter, MockHttpServletRequest request,
                                                MockHttpServletResponse response, int statusToSet) throws Exception {
        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                res.setStatus(statusToSet);
            }
        }));
        var lines = capture.withEvent("http_request");
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    // ------------------------------------------------------------------ the line

    @Test
    void successfulRequest_writesOneInfoLineWithTheAgreedFields() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request("GET", "/api/inventory"), response, 200);

        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.message()).matches("HTTP GET /api/inventory -> 200 in \\d+ ms");
        assertThat(line.text("httpMethod")).isEqualTo("GET");
        assertThat(line.text("httpPath")).isEqualTo("/api/inventory");
        assertThat(line.number("httpStatus")).isEqualTo(200);
        assertThat(line.number("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(line.text("clientIp")).isEqualTo("192.0.2.10");
        assertThat(line.text("userId")).isEqualTo("anonymous");
        assertThat(line.has("slow")).isFalse();
        assertThat(line.logger()).isEqualTo(RequestLoggingFilter.class.getName());
    }

    @Test
    void theQueryString_isNeverInTheLine() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/inventory");
        request.setQueryString("token=hunter2&q=secret");
        request.addParameter("token", "hunter2");

        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request, new MockHttpServletResponse(), 200);

        assertThat(line.raw()).doesNotContain("hunter2").doesNotContain("secret").doesNotContain("?");
    }

    @Test
    void aPathWithLineBreaks_isCleanedInTheLine() throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000),
                request("GET", "/api/x\r\n{\"event\":\"forged\"}"), new MockHttpServletResponse(), 404);

        assertThat(line.text("httpPath")).isEqualTo("/api/x__{\"event\":\"forged\"}");
        assertThat(line.message()).doesNotContain("\r").doesNotContain("\n");
        assertThat(line.text("event")).isEqualTo("http_request");
    }

    @Test
    void aVeryLongPath_isCutAt200Characters() throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000),
                request("GET", "/" + "a".repeat(1000)), new MockHttpServletResponse(), 404);

        assertThat(line.text("httpPath")).hasSize(200);
    }

    @Test
    void forwardedFor_winsOverTheSocketAddress() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/inventory");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request, new MockHttpServletResponse(), 200);

        assertThat(line.text("clientIp")).isEqualTo("203.0.113.7");
    }

    // ------------------------------------------------------------------ levels

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/healthz"})
    void healthChecks_areDebug(String path) throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request("GET", path),
                new MockHttpServletResponse(), 200);

        assertThat(line.level()).isEqualTo("DEBUG");
    }

    @Test
    void healthChecks_areNotWrittenAtInfo_whenTheLoggerIsAtInfo() throws Exception {
        filterLogger.setLevel(Level.INFO);

        new RequestLoggingFilter(2000).doFilter(request("GET", "/actuator/health"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(capture.withEvent("http_request")).isEmpty();
    }

    @Test
    void aFailingHealthCheck_isStillAnError() throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request("GET", "/actuator/health"),
                new MockHttpServletResponse(), 503);

        assertThat(line.level()).isEqualTo("ERROR");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void unauthorizedAndForbidden_areWarn(int status) throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request("POST", "/api/inventory"),
                new MockHttpServletResponse(), status);

        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.number("httpStatus")).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 400, 422})
    void otherClientErrors_areInfo(int status) throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request("GET", "/api/inventory/1"),
                new MockHttpServletResponse(), status);

        assertThat(line.level()).isEqualTo("INFO");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverErrors_areError(int status) throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(2000), request("GET", "/api/inventory/1"),
                new MockHttpServletResponse(), status);

        assertThat(line.level()).isEqualTo("ERROR");
    }

    @Test
    void aSlowRequest_isWarn_withSlowTrue() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(5);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/inventory"), response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        LogCapture.Line line = capture.withEvent("http_request").get(0);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.get("slow")).isEqualTo(true);
        assertThat(line.number("durationMs")).isGreaterThanOrEqualTo(50);
    }

    @Test
    void aFastRequest_hasNoSlowField() throws Exception {
        LogCapture.Line line = runAndGetAccessLine(new RequestLoggingFilter(60_000), request("GET", "/api/inventory"),
                new MockHttpServletResponse(), 200);

        assertThat(line.has("slow")).isFalse();
        assertThat(line.level()).isEqualTo("INFO");
    }

    // ------------------------------------------------------------------ requests that throw

    @Test
    void aRequestThatThrows_isLoggedAs500Error_andTheExceptionIsRethrown() {
        MockHttpServletRequest request = request("GET", "/api/inventory");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestLoggingFilter filter = new RequestLoggingFilter(2000);

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        LogCapture.Line line = capture.withEvent("http_request").get(0);
        assertThat(line.level()).isEqualTo("ERROR");
        assertThat(line.number("httpStatus")).isEqualTo(500);
        assertThat(line.has("stack_trace")).isFalse();
        for (String key : OUR_KEYS) {
            assertThat(MDC.get(key)).as("MDC key " + key + " after a request that threw").isNull();
        }
    }

    @Test
    void anIOException_isRethrownToo() {
        RequestLoggingFilter filter = new RequestLoggingFilter(2000);

        assertThatThrownBy(() -> filter.doFilter(request("GET", "/x"), new MockHttpServletResponse(), (req, res) -> {
            throw new IOException("closed");
        })).isInstanceOf(IOException.class);
        assertThat(capture.withEvent("http_request")).hasSize(1);
    }

    @Test
    void aServletException_isRethrownToo() {
        RequestLoggingFilter filter = new RequestLoggingFilter(2000);

        assertThatThrownBy(() -> filter.doFilter(request("GET", "/x"), new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("broken");
        })).isInstanceOf(ServletException.class);
        assertThat(capture.withEvent("http_request").get(0).number("httpStatus")).isEqualTo(500);
    }

    // ------------------------------------------------------------------ MDC

    @Test
    void mdcKeys_areSetDuringTheRequest_andRemovedAfterwards() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/inventory");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        String[] seenInside = new String[1];

        new RequestLoggingFilter(2000).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seenInside[0] = MDC.get("clientIp");
            // what UserMdcFilter does, inside the chain
            MDC.put("userId", "42");
            MDC.put("userEmail", "someone@example.com");
            MDC.put("userRole", "USER");
        });

        assertThat(seenInside[0]).isEqualTo("203.0.113.7");
        LogCapture.Line line = capture.withEvent("http_request").get(0);
        assertThat(line.text("userId")).isEqualTo("42");
        assertThat(line.text("userEmail")).isEqualTo("someone@example.com");
        assertThat(line.text("userRole")).isEqualTo("USER");
        for (String key : OUR_KEYS) {
            assertThat(MDC.get(key)).as("MDC key " + key + " after the request").isNull();
        }
    }

    @Test
    void theTraceId_isNotRemoved_becauseTheTracingLibraryOwnsIt() throws Exception {
        MDC.put("traceId", "abc123");

        new RequestLoggingFilter(2000).doFilter(request("GET", "/api/inventory"), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(MDC.get("traceId")).isEqualTo("abc123");
        assertThat(capture.withEvent("http_request").get(0).text("traceId")).isEqualTo("abc123");
    }

    @Test
    void theTraceId_isReturnedInTheResponseHeader() throws Exception {
        MDC.put("traceId", "abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestLoggingFilter(2000).doFilter(request("GET", "/api/inventory"), response, new MockFilterChain());

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("abc123");
    }

    @Test
    void withoutATrace_noTraceHeaderIsSet() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestLoggingFilter(2000).doFilter(request("GET", "/api/inventory"), response, new MockFilterChain());

        assertThat(response.containsHeader("X-Trace-Id")).isFalse();
    }

    @Test
    void theSecondRequestOnTheSameThread_doesNotSeeTheFirstOnesValues() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(2000);
        MockHttpServletRequest first = request("GET", "/first");
        first.addHeader("X-Forwarded-For", "203.0.113.7");
        filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> MDC.put("userId", "42"));

        filter.doFilter(request("GET", "/second"), new MockHttpServletResponse(), new MockFilterChain());

        LogCapture.Line second = capture.accessLines("/second").get(0);
        assertThat(second.text("clientIp")).isEqualTo("192.0.2.10");
        assertThat(second.text("userId")).isEqualTo("anonymous");
    }

    // ------------------------------------------------------------------ dispatches

    @Test
    void aRequestThatPassesTheFilterAgain_writesOneLine() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(2000);
        MockHttpServletRequest request = request("GET", "/api/inventory");

        // for example a forward inside the chain: the same request meets the same filter a second time
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> filter.doFilter(req, res, new MockFilterChain()));

        assertThat(capture.withEvent("http_request")).hasSize(1);
    }

    @Test
    void anErrorDispatch_isNotLoggedAgain() throws Exception {
        MockHttpServletRequest request = request("GET", "/error");
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/api/inventory");

        new RequestLoggingFilter(2000).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(capture.withEvent("http_request")).isEmpty();
    }

    // ------------------------------------------------------------------ logging must never break a request

    @Test
    void aFailureWhileReadingTheForwardedHeader_doesNotBreakTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inventory") {
            @Override
            public String getHeader(String name) {
                throw new IllegalStateException("header trouble");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainRan = {false};

        new RequestLoggingFilter(2000).doFilter(request, response, (req, res) -> {
            chainRan[0] = true;
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
        });

        assertThat(chainRan[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(201);
    }

    @Test
    void aFailureWhileReadingThePath_doesNotBreakTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inventory") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("uri trouble");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainRan = {false};

        new RequestLoggingFilter(2000).doFilter(request, response, (req, res) -> {
            chainRan[0] = true;
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(202);
        });

        assertThat(chainRan[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(202);
        for (String key : OUR_KEYS) {
            assertThat(MDC.get(key)).isNull();
        }
    }
}
