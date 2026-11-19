package com.microservices.order.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.argument.StructuredArgument;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Collects every log event of the test JVM (all loggers, including the "audit" logger) while a test runs.
 *
 * Each event is turned into a plain {@link Line} at the moment it is logged, because Logback reads the MDC of an
 * event lazily: the MDC values and the structured fields are the ones of the thread that wrote the line.
 * The levels of the service's own loggers are set to DEBUG while the capture is open, so DEBUG events can be tested
 * whatever profile the shared JVM was started with. The old levels are restored by {@link #close()}.
 */
public final class LogCapture implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final List<String> LOGGERS_SET_TO_DEBUG =
            List.of("com.microservices", "com.microservices.order", "audit");

    /** One captured log event. */
    public record Line(String level, String logger, String thread, String message, Map<String, String> mdc,
                       Map<String, Object> fields, String stackTrace) {

        public boolean isEvent(String name) {
            return name.equals(fields.get("event"));
        }

        public Object field(String name) {
            return fields.get(name);
        }

        public String mdc(String name) {
            return mdc.get(name);
        }

        /**
         * True if the message, an MDC value or a text field has a CR, LF or other control character: such a line
         * could be split into two lines by a plain-text reader. (A stack trace has line breaks on purpose.)
         */
        public boolean hasControlCharacters() {
            java.util.stream.Stream<String> texts = java.util.stream.Stream.concat(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(message), mdc.values().stream()),
                    fields.values().stream().filter(String.class::isInstance).map(String.class::cast));
            return texts.anyMatch(text -> text.chars().anyMatch(Character::isISOControl));
        }

        /** Everything the line says, as one string (message, MDC, fields, stack trace), for "never appears" checks. */
        public String text() {
            return message + "\n" + mdc + "\n" + fields + "\n" + (stackTrace == null ? "" : stackTrace);
        }
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<Line> lines = new CopyOnWriteArrayList<>();
        private final ThreadLocal<ILoggingEvent> last = new ThreadLocal<>();

        @Override
        protected void append(ILoggingEvent event) {
            // The same event can arrive twice when a logger is attached below the root (additivity): keep one.
            if (last.get() == event) {
                return;
            }
            last.set(event);
            Map<String, Object> fields = new LinkedHashMap<>();
            collectFields(event.getArgumentArray(), fields);
            Map<String, String> mdc = event.getMDCPropertyMap() == null
                    ? Map.of() : new LinkedHashMap<>(event.getMDCPropertyMap());
            String stackTrace = event.getThrowableProxy() == null
                    ? null : ThrowableProxyUtil.asString(event.getThrowableProxy());
            lines.add(new Line(event.getLevel().toString(), event.getLoggerName(), event.getThreadName(),
                    event.getFormattedMessage(), mdc, fields, stackTrace));
        }
    }

    private final CapturingAppender appender = new CapturingAppender();
    private final Map<Logger, Level> oldLevels = new LinkedHashMap<>();
    private final List<Logger> attachedTo = new ArrayList<>();

    private LogCapture() {
    }

    /** Starts collecting. Use it in a try-with-resources block or close it in an after-each method. */
    public static LogCapture start() {
        LogCapture capture = new LogCapture();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        capture.appender.setContext(root.getLoggerContext());
        capture.appender.start();
        root.addAppender(capture.appender);
        capture.attachedTo.add(root);
        // "audit" does not pass its events on to the root logger, so it gets the appender too.
        Logger audit = (Logger) LoggerFactory.getLogger("audit");
        audit.addAppender(capture.appender);
        capture.attachedTo.add(audit);
        for (String name : LOGGERS_SET_TO_DEBUG) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            capture.oldLevels.put(logger, logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
        return capture;
    }

    @Override
    public void close() {
        attachedTo.forEach(logger -> logger.detachAppender(appender));
        appender.stop();
        oldLevels.forEach(Logger::setLevel);
    }

    // ------------------------------------------------------------------------------------ reading

    public List<Line> lines() {
        return List.copyOf(appender.lines);
    }

    public List<Line> lines(Predicate<Line> filter) {
        return appender.lines.stream().filter(filter).toList();
    }

    /**
     * The lines written by this service itself (its own loggers and the audit logger). Other libraries may log
     * what they like (for example Hibernate prints multi-line SQL at DEBUG when an earlier dev-profile test of the
     * same JVM switched that logger on), so "no control characters" is a statement about our own lines.
     */
    public List<Line> ownLines() {
        return lines(line -> line.logger().startsWith("com.microservices") || line.logger().equals("audit"));
    }

    /** All lines with this audit or other structured event name. */
    public List<Line> events(String event) {
        return lines(line -> line.isEvent(event));
    }

    /** The only line with this event; fails if there is none or more than one. */
    public Line single(String event) {
        List<Line> found = events(event);
        if (found.size() != 1) {
            throw new AssertionError("Expected exactly one '" + event + "' event but found " + found.size()
                    + ": " + found);
        }
        return found.get(0);
    }

    /** The access lines (event http_request) of one HTTP method and path. */
    public List<Line> accessLines(String method, String path) {
        return lines(line -> line.isEvent("http_request") && method.equals(line.field("httpMethod"))
                && path.equals(line.field("httpPath")));
    }

    public Line accessLine(String method, String path) {
        List<Line> found = accessLines(method, path);
        if (found.size() != 1) {
            throw new AssertionError("Expected exactly one access line for " + method + " " + path + " but found "
                    + found.size() + ": " + found);
        }
        return found.get(0);
    }

    /** Everything that was logged, as one string: for "this must never appear in any log" checks. */
    public String allText() {
        StringBuilder text = new StringBuilder();
        appender.lines.forEach(line -> text.append(line.text()).append('\n'));
        return text.toString();
    }

    // ------------------------------------------------------------------------------------ structured fields

    private static void collectFields(Object[] arguments, Map<String, Object> into) {
        if (arguments == null) {
            return;
        }
        for (Object argument : arguments) {
            if (!(argument instanceof StructuredArgument structured)) {
                continue;
            }
            try (StringWriter out = new StringWriter(); JsonGenerator generator = MAPPER.getFactory().createGenerator(out)) {
                generator.writeStartObject();
                structured.writeTo(generator);
                generator.writeEndObject();
                generator.flush();
                JsonNode node = MAPPER.readTree(out.toString());
                node.fields().forEachRemaining(entry -> into.put(entry.getKey(), toJava(entry.getValue())));
            } catch (Exception e) {
                throw new IllegalStateException("A structured argument could not be read", e);
            }
        }
    }

    private static Object toJava(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }
}
