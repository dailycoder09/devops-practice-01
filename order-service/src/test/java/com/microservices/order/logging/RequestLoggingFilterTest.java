package com.microservices.order.logging;

import com.microservices.order.support.LogCapture;
import com.microservices.order.support.LogCapture.Line;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter that writes one access line per request, sets X-Trace-Id and the client IP, and cleans the MDC.
 * (The same behaviour through the whole service is tested in {@link AccessLogIntegrationTest}.)
 */
class RequestLoggingFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final long NOT_SLOW = 60_000;

    private LogCapture logs;

    @BeforeEach
    void startCapture() {
        MDC.clear();
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
        MDC.clear();
    }

    private interface ChainBody {
        void run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception;
    }

    private static ObjectProvider<Tracer> noTracer() {
        return new StaticListableBeanFactory().getBeanProvider(Tracer.class);
    }

    private static Tracer tracerWithTrace(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        return tracer;
    }

    private static ObjectProvider<Tracer> provider(Tracer tracer) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        return factory.getBeanProvider(Tracer.class);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("192.0.2.10");
        return request;
    }

    private static MockHttpServletResponse run(RequestLoggingFilter filter, MockHttpServletRequest request,
                                               ChainBody body) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            try {
                body.run((MockHttpServletRequest) req, (MockHttpServletResponse) res);
            } catch (IOException | ServletException | RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletResponse run(String method, String uri, int status) throws Exception {
        return run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request(method, uri),
                (req, res) -> res.setStatus(status));
    }

    private Line theAccessLine() {
        return logs.single("http_request");
    }

    // ================================================================== the access line

    @Test
    void accessLine_hasTheMessageAndTheFieldsOfTheContract() throws Exception {
        run("GET", "/api/products", 200);

        Line line = theAccessLine();
        assertThat(line.message()).matches("HTTP GET /api/products -> 200 in \\d+ ms");
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.field("event")).isEqualTo("http_request");
        assertThat(line.field("httpMethod")).isEqualTo("GET");
        assertThat(line.field("httpPath")).isEqualTo("/api/products");
        assertThat(line.field("httpStatus")).isEqualTo(200);
        assertThat(line.field("durationMs")).isInstanceOf(Number.class);
        assertThat(((Number) line.field("durationMs")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(line.mdc("clientIp")).isEqualTo("192.0.2.10");
        assertThat(line.mdc("userId")).isEqualTo("anonymous");
    }

    @Test
    void durationMs_isTheTimeTheRequestTook() throws Exception {
        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/x"), (req, res) -> Thread.sleep(80));

        assertThat(((Number) theAccessLine().field("durationMs")).longValue()).isBetween(70L, 5_000L);
    }

    @Test
    void exactlyOneLine_perRequest_evenWhenTheFilterIsReachedTwiceForTheSameRequest() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(noTracer(), NOT_SLOW);
        MockHttpServletRequest request = request("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // The same request goes through the filter again (a forward or a second registration).
        filter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, (r, s) -> { }));

        assertThat(logs.events("http_request")).hasSize(1);
    }

    @Test
    void anErrorDispatch_isNotLoggedAgain() throws Exception {
        MockHttpServletRequest request = request("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        // This attribute is what Spring's OncePerRequestFilter looks at to know that this is the container's error dispatch.
        request.setAttribute("jakarta.servlet.error.request_uri", "/api/orders");
        AtomicInteger chainRuns = new AtomicInteger();

        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request, (req, res) -> chainRuns.incrementAndGet());

        assertThat(chainRuns).hasValue(1);
        assertThat(logs.events("http_request")).isEmpty();
    }

    @Test
    void theQueryString_isNeverLogged() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/orders");
        request.setQueryString("page=0&token=SECRET-QUERY-VALUE&email=ann@example.com");

        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request, (req, res) -> res.setStatus(200));

        assertThat(logs.allText()).doesNotContain("SECRET-QUERY-VALUE").doesNotContain("token=")
                .doesNotContain("ann@example.com").doesNotContain("page=0");
        assertThat(theAccessLine().field("httpPath")).isEqualTo("/api/orders");
    }

    @Test
    void thePath_isCleaned_noControlCharactersAndAtMost200Characters() throws Exception {
        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/x\r\nINFO fake line"),
                (req, res) -> res.setStatus(404));
        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/" + "y".repeat(5000)),
                (req, res) -> res.setStatus(404));

        assertThat(logs.events("http_request")).hasSize(2);
        Line first = logs.events("http_request").get(0);
        Line second = logs.events("http_request").get(1);
        assertThat(first.field("httpPath")).isEqualTo("/api/x__INFO fake line");
        assertThat(first.message()).doesNotContain("\r").doesNotContain("\n");
        assertThat((String) second.field("httpPath")).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void theMethod_isCleaned_too() throws Exception {
        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("G\nET", "/api/x"), (req, res) -> { });

        assertThat(theAccessLine().field("httpMethod")).isEqualTo("G_ET");
    }

    // ================================================================== levels

    @ParameterizedTest(name = "{0} {1} -> {2} is logged at {3}")
    @CsvSource({
            "GET,/api/products,200,INFO",
            "POST,/api/orders,201,INFO",
            "DELETE,/api/cart,204,INFO",
            "GET,/api/products,304,INFO",
            "POST,/api/orders,400,INFO",
            "GET,/api/orders/5,404,INFO",
            "POST,/api/orders,409,INFO",
            "POST,/api/orders,422,INFO",
            "GET,/api/orders,401,WARN",
            "GET,/api/orders,403,WARN",
            "POST,/api/orders,500,ERROR",
            "POST,/api/orders,503,ERROR",
            "GET,/api/orders,599,ERROR",
            "GET,/actuator/health,200,DEBUG",
            "GET,/actuator/health/liveness,200,DEBUG",
            "GET,/actuator/health/readiness,200,DEBUG",
            "HEAD,/actuator/health,200,DEBUG",
            "GET,/healthz,200,DEBUG",
            "GET,/actuator/health,503,ERROR",
            "GET,/actuator/health,401,WARN",
            "GET,/actuator/prometheus,200,DEBUG",
            "GET,/actuator/metrics/jvm.memory.used,200,DEBUG",
            "GET,/actuator/info,200,DEBUG",
            "GET,/actuator/env,200,INFO",
            "GET,/actuator/loggers/ROOT,200,INFO",
            "POST,/actuator/loggers/ROOT,204,INFO",
    })
    void level_followsTheStatusAndThePath(String method, String path, int status, String expectedLevel)
            throws Exception {
        run(method, path, status);

        Line line = theAccessLine();
        assertThat(line.level()).isEqualTo(expectedLevel);
        assertThat(line.field("httpStatus")).isEqualTo(status);
        assertThat(line.field("slow")).isNull();
    }

    @Test
    void aSlowRequest_isWarn_withSlowTrue() throws Exception {
        run(new RequestLoggingFilter(noTracer(), 30), request("GET", "/api/orders"), (req, res) -> {
            Thread.sleep(120);
            res.setStatus(200);
        });

        Line line = theAccessLine();
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.field("slow")).isEqualTo(true);
        assertThat(line.field("httpStatus")).isEqualTo(200);
    }

    @Test
    void aSlowHealthCheck_isAlsoWarn() throws Exception {
        run(new RequestLoggingFilter(noTracer(), 30), request("GET", "/actuator/health"), (req, res) -> {
            Thread.sleep(120);
            res.setStatus(200);
        });

        assertThat(theAccessLine().level()).isEqualTo("WARN");
        assertThat(theAccessLine().field("slow")).isEqualTo(true);
    }

    @Test
    void aSlowFailure_keepsItsHigherLevel_andIsMarkedSlow() throws Exception {
        run(new RequestLoggingFilter(noTracer(), 30), request("GET", "/api/orders"), (req, res) -> {
            Thread.sleep(120);
            res.setStatus(503);
        });

        assertThat(theAccessLine().level()).isEqualTo("ERROR");
        assertThat(theAccessLine().field("slow")).isEqualTo(true);
    }

    @Test
    void aFastRequest_isNotMarkedSlow() throws Exception {
        run("GET", "/api/orders", 200);

        assertThat(theAccessLine().fields()).doesNotContainKey("slow");
    }

    // ================================================================== trace id

    @Test
    void theTraceId_isReturnedInTheResponseHeader() throws Exception {
        MockHttpServletResponse response = run(new RequestLoggingFilter(provider(tracerWithTrace(TRACE_ID)), NOT_SLOW),
                request("GET", "/api/x"), (req, res) -> res.setStatus(200));

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
    }

    @Test
    void theHeader_isThereWhenTheRequestIsRejectedWith401_andWhenItThrows() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter(provider(tracerWithTrace(TRACE_ID)), NOT_SLOW);

        MockHttpServletResponse rejected = run(filter, request("GET", "/api/x"), (req, res) -> res.setStatus(401));
        MockHttpServletResponse thrown = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request("GET", "/api/x"), thrown, (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rejected.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
        assertThat(thrown.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
    }

    @Test
    void withoutATracer_thereIsNoHeader() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/x", 200);

        assertThat(response.containsHeader("X-Trace-Id")).isFalse();
    }

    @Test
    void withATracerButNoCurrentSpan_thereIsNoHeader() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        MockHttpServletResponse response = run(new RequestLoggingFilter(provider(tracer), NOT_SLOW),
                request("GET", "/api/x"), (req, res) -> res.setStatus(200));

        assertThat(response.containsHeader("X-Trace-Id")).isFalse();
    }

    @Test
    void ifTheTraceIdIsOnlyInTheMdc_thatOneIsUsed() throws Exception {
        MDC.put("traceId", "abc123abc123abc1");

        MockHttpServletResponse response = run("GET", "/api/x", 200);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("abc123abc123abc1");
    }

    @Test
    void aTracerThatFails_neverBreaksTheRequest() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenThrow(new IllegalStateException("tracer is broken"));

        MockHttpServletResponse response = run(new RequestLoggingFilter(provider(tracer), NOT_SLOW),
                request("GET", "/api/x"), (req, res) -> res.setStatus(201));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.containsHeader("X-Trace-Id")).isFalse();
        assertThat(theAccessLine().field("httpStatus")).isEqualTo(201);
    }

    // ================================================================== client IP, MDC

    @Test
    void theClientIp_isInTheMdcWhileTheRequestRuns_andOnTheAccessLine() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/x");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        AtomicInteger checked = new AtomicInteger();

        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request, (req, res) -> {
            assertThat(MDC.get("clientIp")).isEqualTo("203.0.113.7");
            assertThat(MDC.get("userId")).isEqualTo("anonymous");
            checked.incrementAndGet();
        });

        assertThat(checked).hasValue(1);
        assertThat(theAccessLine().mdc("clientIp")).isEqualTo("203.0.113.7");
    }

    @Test
    void theMdc_isCleanAfterTheRequest_evenForKeysSetInsideTheChain() throws Exception {
        MDC.put("traceId", "owned-by-the-tracing-layer");

        run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/x"), (req, res) -> {
            MDC.put("userId", "42");
            MDC.put("userEmail", "ann@example.com");
            MDC.put("userRole", "USER");
        });

        // The access line was written with the user in it ...
        assertThat(theAccessLine().mdc("userId")).isEqualTo("42");
        assertThat(theAccessLine().mdc("userEmail")).isEqualTo("ann@example.com");
        assertThat(theAccessLine().mdc("userRole")).isEqualTo("USER");
        // ... and afterwards every key of ours is gone, while the keys of other layers are left alone.
        assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("traceId");
    }

    @Test
    void theMdc_isCleanAfterARuntimeException_andTheExceptionGoesOn() throws Exception {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/x"),
                (req, res) -> {
                    MDC.put("userId", "42");
                    throw failure;
                })).isSameAs(failure);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        Line line = theAccessLine();
        assertThat(line.level()).isEqualTo("ERROR");
        assertThat(line.field("httpStatus")).isEqualTo(500);
        assertThat(line.mdc("userId")).isEqualTo("42");
    }

    @Test
    void anIoException_aServletException_andAnError_areRethrown_andLoggedAs500() throws Exception {
        assertThatThrownBy(() -> run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/a"),
                (req, res) -> {
                    throw new IOException("disk");
                })).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/b"),
                (req, res) -> {
                    throw new ServletException("servlet");
                })).isInstanceOf(ServletException.class);
        assertThatThrownBy(() -> run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/c"),
                (req, res) -> {
                    throw new AssertionError("an error");
                })).isInstanceOf(AssertionError.class);

        assertThat(logs.events("http_request")).hasSize(3)
                .allSatisfy(line -> {
                    assertThat(line.level()).isEqualTo("ERROR");
                    assertThat(line.field("httpStatus")).isEqualTo(500);
                });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void ifTheChainHasSetAnErrorStatusBeforeThrowing_thatStatusIsLogged() throws Exception {
        assertThatThrownBy(() -> run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request("GET", "/api/x"),
                (req, res) -> {
                    res.setStatus(503);
                    throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(theAccessLine().field("httpStatus")).isEqualTo(503);
    }

    // ================================================================== logging never breaks a request

    @Test
    void ifTheAccessLineCannotBeWritten_theResponseIsStillTheSame_andTheMdcIsStillCleaned() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("cannot read the path");
            }
        };
        request.setRemoteAddr("192.0.2.10");

        MockHttpServletResponse response = run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request,
                (req, res) -> res.setStatus(201));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void ifTheClientIpCannotBeRead_theRequestStillRuns() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x") {
            @Override
            public String getRemoteAddr() {
                throw new IllegalStateException("no socket");
            }
        };
        AtomicInteger chainRuns = new AtomicInteger();

        MockHttpServletResponse response = run(new RequestLoggingFilter(noTracer(), NOT_SLOW), request,
                (req, res) -> {
                    chainRuns.incrementAndGet();
                    res.setStatus(202);
                });

        assertThat(chainRuns).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void theFilter_runsAfterTheTracingFilter_andBeforeSpringSecurity() {
        int order = new RequestLoggingFilter(noTracer(), NOT_SLOW).getOrder();

        assertThat(order).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        // ServerHttpObservationFilter is HIGHEST_PRECEDENCE + 1, the Spring Security chain is much later.
        assertThat(order).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1).isLessThan(-100);
    }
}
