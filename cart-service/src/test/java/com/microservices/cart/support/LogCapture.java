package com.microservices.cart.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects every log event of the test JVM while it is open, as the JSON the real JSON encoder writes
 * (MDC keys and structured arguments are top-level fields). Used to check what the service logs.
 *
 * The event is "prepared" the way the real async appender does it, so the MDC of the request thread is kept.
 * Levels that a test changes are put back by {@link #close()}.
 */
public final class LogCapture implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final List<String> rawLines = new CopyOnWriteArrayList<>();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private final Map<String, Level> originalLevels = new LinkedHashMap<>();
    private final LogstashEncoder encoder = new LogstashEncoder();
    private final AppenderBase<ILoggingEvent> appender;

    private LogCapture() {
        encoder.setContext(context);
        encoder.start();
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
                rawLines.add(line);
                try {
                    events.add(MAPPER.readTree(line));
                } catch (Exception e) {
                    throw new IllegalStateException("The log line is not valid JSON: " + line, e);
                }
            }
        };
        appender.setContext(context);
        appender.setName("LOG_CAPTURE");
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    /** Sets the level of a logger until {@link #close()} ("ROOT" is the root logger). */
    public LogCapture level(String loggerName, Level level) {
        Logger logger = context.getLogger(loggerName);
        originalLevels.putIfAbsent(loggerName, logger.getLevel());
        logger.setLevel(level);
        return this;
    }

    /** Forgets everything captured so far (for example the lines of the set-up requests of a test). */
    public void clear() {
        rawLines.clear();
        events.clear();
    }

    public List<JsonNode> all() {
        return new ArrayList<>(events);
    }

    /** All events with this "event" field, in the order they were logged. */
    public List<JsonNode> events(String eventName) {
        return events.stream()
                .filter(node -> node.has("event") && eventName.equals(node.get("event").asText()))
                .toList();
    }

    /** The one event with this name (fails if there are none or more than one). */
    public JsonNode single(String eventName) {
        List<JsonNode> found = events(eventName);
        if (found.size() != 1) {
            throw new AssertionError("Expected exactly one '" + eventName + "' event but found " + found.size()
                    + ": " + found);
        }
        return found.get(0);
    }

    /** The access lines (event http_request) for one path. */
    public List<JsonNode> accessLines(String path) {
        return events("http_request").stream()
                .filter(node -> path.equals(node.path("httpPath").asText()))
                .toList();
    }

    public JsonNode singleAccessLine(String path) {
        List<JsonNode> found = accessLines(path);
        if (found.size() != 1) {
            throw new AssertionError("Expected exactly one access line for " + path + " but found " + found.size()
                    + ": " + events("http_request"));
        }
        return found.get(0);
    }

    /** Everything that was logged, as one text, for "this must not appear anywhere" checks. */
    public String allText() {
        return String.join("", rawLines);
    }

    @Override
    public void close() {
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        encoder.stop();
        originalLevels.forEach((name, level) -> context.getLogger(name).setLevel(level));
    }
}
