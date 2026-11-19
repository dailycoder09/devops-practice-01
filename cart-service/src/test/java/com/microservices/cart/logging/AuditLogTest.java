package com.microservices.cart.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.cart.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    private LogCapture logs;

    @BeforeEach
    void start() {
        logs = LogCapture.start();
    }

    @AfterEach
    void stop() {
        logs.close();
    }

    @Test
    void event_isWrittenByTheLoggerNamedAudit_atInfo_withTheEventAndTheFields() {
        AuditLog.event("thing_happened", "A thing happened", kv("itemId", 7), kv("name", "bolt"));

        JsonNode line = logs.single("thing_happened");
        assertThat(line.get("logger_name").asText()).isEqualTo("audit");
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(line.get("message").asText()).isEqualTo("A thing happened");
        assertThat(line.get("itemId").asInt()).isEqualTo(7);
        assertThat(line.get("name").asText()).isEqualTo("bolt");
    }

    @Test
    void warn_andError_useTheirOwnLevel() {
        AuditLog.warn("thing_refused", "Refused", kv("reason", "no"));
        AuditLog.error("thing_broke", "Broke", kv("reason", "bad"));

        assertThat(logs.single("thing_refused").get("level").asText()).isEqualTo("WARN");
        assertThat(logs.single("thing_refused").get("reason").asText()).isEqualTo("no");
        assertThat(logs.single("thing_broke").get("level").asText()).isEqualTo("ERROR");
    }

    @Test
    void theFields_canAlsoBeShownInTheMessage() {
        AuditLog.event("greeted", "Hello {}", kv("who", "bob"));

        JsonNode line = logs.single("greeted");
        assertThat(line.get("message").asText()).isEqualTo("Hello who=bob");
        assertThat(line.get("who").asText()).isEqualTo("bob");
    }

    @Test
    void anEventWithoutFields_works() {
        AuditLog.event("plain_event", "Plain");

        assertThat(logs.single("plain_event").get("message").asText()).isEqualTo("Plain");
    }

    @Test
    void aValueWithQuotesAndLineBreaks_staysOneJsonLine() {
        AuditLog.event("tricky_event", "Tricky", kv("text", "say \"hi\"\nsecond line"));

        assertThat(logs.single("tricky_event").get("text").asText()).isEqualTo("say \"hi\"\nsecond line");
        assertThat(logs.all()).hasSize(1);
    }
}
