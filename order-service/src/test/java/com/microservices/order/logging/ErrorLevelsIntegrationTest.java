package com.microservices.order.logging;

import com.microservices.order.support.LogCapture.Line;
import com.microservices.order.support.LoggingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Levels through the whole service (test 2 of the logging contract): a 500 is ERROR and its stack trace is written
 * once, a slow request is WARN with slow=true, handled 4xx are WARN without a stack trace.
 * Two tiny test-only endpoints produce the 500 and the slow answer.
 */
@Import(ErrorLevelsIntegrationTest.TestEndpoints.class)
@TestPropertySource(properties = "app.logging.slow-request-ms=250")
class ErrorLevelsIntegrationTest extends LoggingTestBase {

    @RestController
    static class TestEndpoints {

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("kaboom: internal detail that must not reach the caller");
        }

        @GetMapping("/test/slow")
        String slow() throws InterruptedException {
            Thread.sleep(500);
            return "late";
        }
    }

    @Test
    void aServerError_isErrorOnTheAccessLine_andItsStackTraceIsWrittenExactlyOnce() throws Exception {
        MvcResult result = mockMvc.perform(getWith("/test/boom", userToken))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andReturn();

        // The answer does not leak the internal message.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("kaboom");
        Line access = logs.accessLine("GET", "/test/boom");
        assertThat(access.level()).isEqualTo("ERROR");
        assertThat(access.field("httpStatus")).isEqualTo(500);
        assertThat(access.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(access.stackTrace()).as("the access line carries no stack trace").isNull();

        List<Line> withStackTrace = logs.lines(line -> line.stackTrace() != null);
        assertThat(withStackTrace).as("one stack trace for one failure").hasSize(1);
        Line error = withStackTrace.get(0);
        assertThat(error.level()).isEqualTo("ERROR");
        assertThat(error.logger()).endsWith("GlobalExceptionHandler");
        assertThat(error.stackTrace()).contains("IllegalStateException").contains("kaboom");
        assertThat(error.mdc("traceId")).isEqualTo(traceIdHeader(result));
        assertThat(error.mdc("userId")).isEqualTo(String.valueOf(userId));
    }

    @Test
    void aSlowRequest_isWarn_withSlowTrue_andTheStatusIsStillThere() throws Exception {
        mockMvc.perform(getWith("/test/slow", userToken)).andExpect(status().isOk());

        Line access = logs.accessLine("GET", "/test/slow");
        assertThat(access.level()).isEqualTo("WARN");
        assertThat(access.field("slow")).isEqualTo(true);
        assertThat(access.field("httpStatus")).isEqualTo(200);
        assertThat(((Number) access.field("durationMs")).longValue()).isGreaterThanOrEqualTo(250);
    }

    @Test
    void aFastRequest_isNotSlow() throws Exception {
        // The first request after the start can be slow (class loading), so look at the second one.
        mockMvc.perform(getWith("/api/orders", userToken)).andExpect(status().isOk());
        mockMvc.perform(getWith("/api/orders", userToken)).andExpect(status().isOk());

        assertThat(logs.accessLines("GET", "/api/orders").get(1).field("slow")).isNull();
    }

    @Test
    void handledClientErrors_areWarn_withoutAStackTrace_andOnlyOncePerRequest() throws Exception {
        // 400: an id that is not a number
        mockMvc.perform(getWith("/api/orders/abc", userToken)).andExpect(status().isBadRequest());
        // 400: the cart is empty
        checkout(userToken).andExpect(status().isBadRequest());
        // 404: an order that is not there
        mockMvc.perform(getWith("/api/orders/987654321", userToken)).andExpect(status().isNotFound());
        // 405: a method the API does not have
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/orders/1").header("Authorization", bearer(userToken))).andExpect(status().isMethodNotAllowed());

        assertThat(logs.lines(line -> line.stackTrace() != null)).as("no handled error needs a stack trace").isEmpty();
        List<Line> handlerLines = logs.lines(line -> line.logger().endsWith("GlobalExceptionHandler"));
        assertThat(handlerLines).hasSize(4).allSatisfy(line -> assertThat(line.level()).isEqualTo("WARN"));
        assertThat(logs.lines(line -> "ERROR".equals(line.level()))).isEmpty();
    }
}
