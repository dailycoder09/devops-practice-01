package com.microservices.product.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: collects every log event of the JVM as the JSON object the real encoder writes
 * (MDC keys and structured arguments are top-level fields), so a test can check fields exactly as
 * somebody reading the log file would see them. Attach with {@link #start()}, detach with {@link #close()}.
 */
public final class LogCapture implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final LogstashEncoder encoder = new LogstashEncoder();
    private final AppenderBase<ILoggingEvent> appender;

    private LogCapture() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        encoder.setContext(context);
        encoder.start();
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                byte[] bytes = encoder.encode(event);
                String raw = new String(bytes, StandardCharsets.UTF_8);
                try {
                    Map<String, Object> json = MAPPER.readValue(bytes, new TypeReference<>() { });
                    lines.add(new Line(raw, json));
                } catch (IOException ex) {
                    throw new IllegalStateException("The encoder wrote a line that is not JSON: " + raw, ex);
                }
            }
        };
        appender.setContext(context);
        appender.setName("TEST_CAPTURE");
        appender.start();
        root.addAppender(appender);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public void clear() {
        lines.clear();
    }

    /** All lines whose {@code event} field has this value. */
    public List<Line> withEvent(String event) {
        return lines.stream().filter(line -> event.equals(line.get("event"))).toList();
    }

    /** The access lines written for this path. */
    public List<Line> accessLines(String path) {
        return withEvent("http_request").stream().filter(line -> path.equals(line.get("httpPath"))).toList();
    }

    /** Everything captured so far as one text, for "must not appear anywhere" checks. */
    public String allText() {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line.raw()));
        return text.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        encoder.stop();
    }

    /** One captured log line: the raw JSON text and the parsed fields. */
    public record Line(String raw, Map<String, Object> json) {

        public Object get(String field) {
            return json.get(field);
        }

        public String text(String field) {
            Object value = json.get(field);
            return value == null ? null : String.valueOf(value);
        }

        public boolean has(String field) {
            return json.containsKey(field);
        }

        public String level() {
            return text("level");
        }

        public String message() {
            return text("message");
        }

        public String logger() {
            return text("logger_name");
        }

        public long number(String field) {
            return ((Number) json.get(field)).longValue();
        }
    }
}
