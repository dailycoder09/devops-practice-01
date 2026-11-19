package com.microservices.order.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.TestLogbackConfigurator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real logback-spring.xml of this service, loaded into its own logger context for each profile (test 4 of the
 * logging contract): outside dev the console gets JSON and the file always gets JSON; every line parses, even with
 * quotes, line breaks and a huge value; the field names are the contract's; a stack trace is one field; the levels,
 * the async queues and the rolling rules are the ones of the plan.
 */
class LogbackConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path logDir;

    private String oldLogPath;
    private PrintStream oldOut;
    private ByteArrayOutputStream stdout;
    private LoggerContext context;

    @BeforeEach
    void captureStdoutAndPointTheFileAtATempDirectory() {
        MDC.clear();
        oldLogPath = System.getProperty("LOG_PATH");
        System.setProperty("LOG_PATH", logDir.toString());
        oldOut = System.out;
        stdout = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        if (context != null) {
            context.stop();
        }
        System.setOut(oldOut);
        if (oldLogPath == null) {
            System.clearProperty("LOG_PATH");
        } else {
            System.setProperty("LOG_PATH", oldLogPath);
        }
        MDC.clear();
    }

    private LoggerContext configure(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "order-service");
        environment.setProperty("spring.profiles.active", profile);
        environment.setActiveProfiles(profile);
        URL xml = getClass().getClassLoader().getResource("logback-spring.xml");
        assertThat(xml).as("logback-spring.xml on the classpath").isNotNull();
        context = TestLogbackConfigurator.configure(xml, environment);
        return context;
    }

    /** Stops the context (this empties the async queues) and returns the console and file output. */
    private record Output(List<String> console, List<String> file) {
    }

    private Output finish() throws IOException {
        context.stop();
        List<String> console = lines(new String(stdout.toByteArray(), StandardCharsets.UTF_8));
        Path jsonFile = logDir.resolve("order-service.json.log");
        List<String> file = Files.exists(jsonFile) ? Files.readAllLines(jsonFile, StandardCharsets.UTF_8) : List.of();
        return new Output(console, file);
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    private static JsonNode parse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("Not valid JSON: " + line, e);
        }
    }

    // ================================================================== JSON outside dev

    @Test
    void outsideDev_theConsoleAndTheFileBothGetOneJsonLinePerEvent() throws Exception {
        configure("test");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        MDC.put("userId", "42");
        MDC.put("userEmail", "ann@example.com");
        MDC.put("userRole", "USER");
        MDC.put("clientIp", "203.0.113.7");
        context.getLogger("com.microservices.order.service.impl.OrderServiceImpl")
                .info("Order saved {}", 12, kv("event", "order_confirmed"), kv("orderId", 12), kv("total", 261.5));
        Output output = finish();

        assertThat(output.console()).hasSize(1);
        assertThat(output.file()).hasSize(1);
        assertThat(parse(output.file().get(0))).isEqualTo(parse(output.console().get(0)));

        JsonNode json = parse(output.file().get(0));
        assertThat(OffsetDateTime.parse(json.get("timestamp").asText()).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(json.get("timestamp").asText()).endsWith("Z");
        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("service").asText()).isEqualTo("order-service");
        assertThat(json.get("profile").asText()).isEqualTo("test");
        assertThat(json.get("thread").asText()).isNotBlank();
        assertThat(json.get("message").asText()).isEqualTo("Order saved 12");
        String shortLogger = json.get("logger").asText();
        assertThat(shortLogger).endsWith("OrderServiceImpl").hasSizeLessThan(
                "com.microservices.order.service.impl.OrderServiceImpl".length());
        // Every MDC key is a top-level field ...
        assertThat(json.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(json.get("spanId").asText()).isEqualTo("00f067aa0ba902b7");
        assertThat(json.get("userId").asText()).isEqualTo("42");
        assertThat(json.get("userEmail").asText()).isEqualTo("ann@example.com");
        assertThat(json.get("userRole").asText()).isEqualTo("USER");
        assertThat(json.get("clientIp").asText()).isEqualTo("203.0.113.7");
        // ... and so is every structured argument, with its JSON type.
        assertThat(json.get("event").asText()).isEqualTo("order_confirmed");
        assertThat(json.get("orderId").isIntegralNumber()).isTrue();
        assertThat(json.get("total").decimalValue()).isEqualByComparingTo("261.5");
    }

    @Test
    void theFieldNamesAreTheContractOnes_andNoDefaultNamesAreLeftOver() throws Exception {
        configure("test");
        context.getLogger("com.microservices.order.Something").info("plain");
        Output output = finish();

        JsonNode json = parse(output.file().get(0));
        assertThat(json.fieldNames()).toIterable().contains("timestamp", "level", "service", "profile", "logger",
                "thread", "message")
                .doesNotContain("@timestamp", "@version", "logger_name", "thread_name", "level_value");
    }

    @Test
    void aMessageWithQuotesLineBreaksAndAVeryLongValue_isStillOneValidJsonLine() throws Exception {
        configure("test");
        String huge = "v".repeat(100_000);
        Logger log = context.getLogger("com.microservices.order.Something");
        log.info("He said \"stop\"\nand then left\r\nthe tab\there, a backslash \\ and a unicode   sign");
        log.info("A very long value: {}", huge);
        log.info("Control \u0000 \u001b characters and {} quotes \"\"\"", "\"x\"");
        Output output = finish();

        for (List<String> lines : List.of(output.console(), output.file())) {
            assertThat(lines).hasSize(3);
            JsonNode first = parse(lines.get(0));
            assertThat(first.get("message").asText())
                    .isEqualTo("He said \"stop\"\nand then left\r\nthe tab\there, a backslash \\ and a unicode   sign");
            assertThat(parse(lines.get(1)).get("message").asText()).isEqualTo("A very long value: " + huge);
            assertThat(parse(lines.get(2)).get("message").asText())
                    .isEqualTo("Control \u0000 \u001b characters and \"x\" quotes \"\"\"");
            // No raw line break may be inside a line (it would split the event in two).
            lines.forEach(line -> assertThat(line).doesNotContain("\n").doesNotContain("\r"));
        }
    }

    @Test
    void aStackTrace_isOneField_notManyLines() throws Exception {
        configure("test");
        Exception failure = new IllegalStateException("outer failure", new RuntimeException("the root cause"));
        context.getLogger("com.microservices.order.Something").error("Something broke", failure);
        context.getLogger("com.microservices.order.Something").info("The next line");
        Output output = finish();

        assertThat(output.file()).hasSize(2);
        assertThat(output.console()).hasSize(2);
        JsonNode json = parse(output.file().get(0));
        assertThat(json.get("level").asText()).isEqualTo("ERROR");
        assertThat(json.get("message").asText()).isEqualTo("Something broke");
        assertThat(json.get("stack_trace").isTextual()).isTrue();
        String trace = json.get("stack_trace").asText();
        assertThat(trace).contains("java.lang.IllegalStateException: outer failure").contains("the root cause")
                .contains("\n").contains("LogbackConfigTest");
        assertThat(json.has("exception")).isFalse();
        assertThat(parse(output.file().get(1)).get("message").asText()).isEqualTo("The next line");
        assertThat(parse(output.file().get(1)).has("stack_trace")).isFalse();
    }

    @Test
    void aHugeStackTrace_isCutToAReasonableSize() throws Exception {
        configure("test");
        Throwable deep = new RuntimeException("deep");
        for (int i = 0; i < 200; i++) {
            deep = new RuntimeException("cause number " + i + " " + "x".repeat(100), deep);
        }
        context.getLogger("com.microservices.order.Something").error("deep", deep);
        Output output = finish();

        JsonNode json = parse(output.file().get(0));
        assertThat(json.get("stack_trace").asText().length()).isLessThan(20_000);
    }

    @Test
    void auditEvents_goToTheSameConsoleAndFile_exactlyOnce() throws Exception {
        configure("test");
        context.getLogger("audit").info("Order confirmed", kv("orderId", 9), kv("event", "order_confirmed"));
        Output output = finish();

        assertThat(output.console()).hasSize(1);
        assertThat(output.file()).hasSize(1);
        assertThat(parse(output.file().get(0)).get("event").asText()).isEqualTo("order_confirmed");
        assertThat(parse(output.file().get(0)).get("logger").asText()).isEqualTo("audit");
    }

    @Test
    void anEventBelowTheLevel_isNotWritten() throws Exception {
        configure("test");
        context.getLogger("com.microservices.order.Something").debug("a developer detail");
        context.getLogger("com.microservices.order.Something").trace("a trace detail");
        context.getLogger("audit").debug("an audit debug");
        Output output = finish();

        assertThat(output.console()).isEmpty();
        assertThat(output.file()).isEmpty();
    }

    // ================================================================== dev

    @Test
    void inDev_theConsoleIsReadableText_withTraceAndUser_andTheFileIsStillJson() throws Exception {
        configure("dev");
        MDC.put("traceId", "trace-1");
        MDC.put("userId", "7");
        MDC.put("clientIp", "203.0.113.7");
        context.getLogger("com.microservices.order.Something").info("hello text");
        context.getLogger("com.microservices.order.Something").debug("a developer detail");
        Output output = finish();

        assertThat(output.console()).hasSize(2);
        assertThat(output.console().get(0))
                .matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} INFO  \\[trace-1\\] \\[user=7 ip=203\\.0\\.113\\.7\\] \\S+ - hello text");
        assertThat(output.console().get(1)).contains("DEBUG").contains("a developer detail");
        assertThat(output.file()).hasSize(2);
        JsonNode json = parse(output.file().get(0));
        assertThat(json.get("profile").asText()).isEqualTo("dev");
        assertThat(json.get("message").asText()).isEqualTo("hello text");
        assertThat(json.get("traceId").asText()).isEqualTo("trace-1");
    }

    @Test
    void inDev_theTextPatternWorksWithoutAnyMdc() throws Exception {
        configure("dev");
        context.getLogger("com.microservices.order.Something").info("no mdc");
        Output output = finish();

        assertThat(output.console().get(0)).contains("[] [user= ip=]").contains("no mdc");
    }

    @Test
    void inDev_aStackTraceInTheConsoleIsPrintedNormally() throws Exception {
        configure("dev");
        context.getLogger("com.microservices.order.Something").error("broken", new IllegalStateException("dev failure"));
        Output output = finish();

        assertThat(String.join("\n", output.console())).contains("java.lang.IllegalStateException: dev failure");
    }

    // ================================================================== levels

    @Test
    void outsideDev_theLevelsAreTheCalmOnes_andSqlParametersAreNeverTraced() {
        configure("prod");

        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.INFO);
        assertThat(context.getLogger("com.microservices.order.service.impl.OrderServiceImpl").getEffectiveLevel())
                .isEqualTo(Level.INFO);
        assertThat(context.getLogger("audit").getLevel()).isEqualTo(Level.INFO);
        assertThat(context.getLogger("org.hibernate").getEffectiveLevel()).isEqualTo(Level.INFO);
        assertThat(context.getLogger("org.hibernate.SQL").getEffectiveLevel()).isEqualTo(Level.INFO);
        assertThat(context.getLogger("org.hibernate.type.descriptor.sql.BasicBinder").getEffectiveLevel())
                .isEqualTo(Level.INFO);
    }

    @Test
    void inDev_ourOwnLoggersAndTheSqlAreDebug_butSqlParametersAreStillNotLogged() {
        configure("dev");

        assertThat(context.getLogger("com.microservices.order.service.impl.OrderServiceImpl").getEffectiveLevel())
                .isEqualTo(Level.DEBUG);
        assertThat(context.getLogger("org.hibernate.SQL").getEffectiveLevel()).isEqualTo(Level.DEBUG);
        assertThat(context.getLogger("org.hibernate.type.descriptor.sql.BasicBinder").getEffectiveLevel())
                .isEqualTo(Level.INFO);
        assertThat(context.getLogger("audit").getLevel()).isEqualTo(Level.INFO);
        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void theAuditLoggerDoesNotPassItsEventsToTheRoot_soNothingIsWrittenTwice() {
        configure("test");

        assertThat(context.getLogger("audit").isAdditive()).isFalse();
    }

    // ================================================================== appenders

    @Test
    void bothAppenders_areAsync_withAQueueOf1024_thatNeverDiscards() {
        configure("test");
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        for (String name : List.of("ASYNC_CONSOLE", "ASYNC_FILE")) {
            Appender<?> appender = root.getAppender(name);
            assertThat(appender).as(name).isInstanceOf(AsyncAppender.class);
            AsyncAppender async = (AsyncAppender) appender;
            assertThat(async.getQueueSize()).as(name).isEqualTo(1024);
            assertThat(async.getDiscardingThreshold()).as(name).isZero();
        }
        Logger audit = context.getLogger("audit");
        assertThat(audit.getAppender("ASYNC_CONSOLE")).isNotNull();
        assertThat(audit.getAppender("ASYNC_FILE")).isNotNull();
    }

    @Test
    void theFileRollsDailyAndAt10Mb_keeps14Days_andIsCappedAt500Mb() {
        configure("test");
        AsyncAppender async = (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC_FILE");

        Appender<?> file = async.getAppender("FILE");

        assertThat(file).isInstanceOf(RollingFileAppender.class);
        RollingFileAppender<?> rolling = (RollingFileAppender<?>) file;
        assertThat(rolling.getFile()).isEqualTo(logDir.resolve("order-service.json.log").toString());
        assertThat(rolling.getRollingPolicy()).isInstanceOf(SizeAndTimeBasedRollingPolicy.class);
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) rolling.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(14);
        assertThat(((FileSize) ReflectionTestUtils.getField(policy, "totalSizeCap")).getSize())
                .isEqualTo(500L * 1024 * 1024);
        assertThat(((FileSize) ReflectionTestUtils.getField(policy, "maxFileSize")).getSize())
                .isEqualTo(10L * 1024 * 1024);
        assertThat(policy.getFileNamePattern()).contains("%d{yyyy-MM-dd}").contains("%i").endsWith(".json.log");
    }

    @Test
    void theOldPlainTextErrorAndHandMadeJsonFiles_areNotWrittenAnyMore() throws Exception {
        configure("test");
        context.getLogger("com.microservices.order.Something").info("an info line");
        context.getLogger("com.microservices.order.Something").error("an error line");
        finish();

        try (var files = Files.list(logDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("order-service.json.log");
        }
    }

    @Test
    void theFileLocation_comesFromLOG_PATH() throws Exception {
        configure("test");
        context.getLogger("com.microservices.order.Something").info("where am I");
        Output output = finish();

        assertThat(output.file()).hasSize(1);
        assertThat(Files.exists(logDir.resolve("order-service.json.log"))).isTrue();
    }

    @Test
    void manyEventsFromManyThreads_allArriveAsValidJson_noneIsLost() throws Exception {
        configure("test");
        Logger log = context.getLogger("com.microservices.order.Something");
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    log.info("thread {} event {}", id, i, kv("n", i));
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        Output output = finish();

        assertThat(output.file()).hasSize(2000);
        assertThat(output.console()).hasSize(2000);
        output.file().forEach(LogbackConfigTest::parse);
    }
}
