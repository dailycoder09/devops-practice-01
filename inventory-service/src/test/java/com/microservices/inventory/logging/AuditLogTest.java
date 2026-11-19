package com.microservices.inventory.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuditLogTest {

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.start();
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void event_writesToTheAuditLogger_atInfo_withTheEventAndTheGivenFields() {
        AuditLog.event("thing_done", "Thing {} done", v("thingId", 5), kv("note", "ok"));

        LogCapture.Line line = capture.lines().get(0);
        assertThat(line.logger()).isEqualTo("audit");
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.message()).isEqualTo("Thing 5 done");
        assertThat(line.text("event")).isEqualTo("thing_done");
        assertThat(line.number("thingId")).isEqualTo(5);
        assertThat(line.text("note")).isEqualTo("ok");
    }

    @Test
    void warn_writesTheSameShape_atWarn() {
        AuditLog.warn("thing_refused", "Thing refused", kv("reason", "no"));

        LogCapture.Line line = capture.lines().get(0);
        assertThat(line.logger()).isEqualTo("audit");
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.text("event")).isEqualTo("thing_refused");
        assertThat(line.text("reason")).isEqualTo("no");
    }

    @Test
    void aMessageWithoutPlaceholders_isWrittenAsIs() {
        AuditLog.event("plain", "Plain message", kv("a", 1), kv("b", 2));

        LogCapture.Line line = capture.lines().get(0);
        assertThat(line.message()).isEqualTo("Plain message");
        assertThat(line.number("a")).isEqualTo(1);
        assertThat(line.number("b")).isEqualTo(2);
    }

    @Test
    void anEvent_writtenWithNullArguments_doesNotThrow() {
        assertThatCode(() -> AuditLog.event("null_args", null, (Object[]) null)).doesNotThrowAnyException();
        assertThatCode(() -> AuditLog.event(null, "no event name")).doesNotThrowAnyException();
        assertThatCode(() -> AuditLog.warn("x", "message {} {}", v("only", "one"))).doesNotThrowAnyException();
    }
}
