package com.microservices.product.logging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test 4: the real log output. A separate JVM starts the real Spring Boot logging set-up
 * (logback-spring.xml, application.yml, the chosen profile) and writes a fixed set of lines. We read what
 * it wrote to stdout and to the rolling JSON file and check every line.
 */
class JsonLogOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Run(List<String> stdout, List<String> file) {
    }

    /** The test classpath without Spring Boot devtools: a deployed jar does not contain it, and it prints plain text. */
    private static String classpathWithoutDevtools() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("spring-boot-devtools"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private Run runProbe(Path logDir, String profile) throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(javaBinary, "-Xlog:disable",
                "-DLOG_PATH=" + logDir, "-cp", classpathWithoutDevtools(),
                LoggingProbeMain.class.getName(), "--spring.profiles.active=" + profile));
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        process.getOutputStream().close();
        // read stdout and stderr in the background, so a full pipe can never block the child
        var stdout = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        var stderr = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("the probe JVM finished in time").isTrue();
        List<String> out = stdout.get(5, TimeUnit.SECONDS);
        assertThat(process.exitValue()).as("probe exit code, stderr: " + stderr.get(5, TimeUnit.SECONDS)).isZero();
        Path jsonFile = logDir.resolve("product-service.json.log");
        assertThat(jsonFile).as("the rolling JSON file").exists();
        return new Run(out, Files.readAllLines(jsonFile, StandardCharsets.UTF_8));
    }

    private static List<String> read(java.io.InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> parse(String line) {
        try {
            return MAPPER.readValue(line, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new AssertionError("Not valid JSON: " + line.substring(0, Math.min(300, line.length())), ex);
        }
    }

    private static Map<String, Object> probeLine(List<String> lines, String messageStart) {
        return lines.stream().map(JsonLogOutputTest::parse)
                .filter(json -> String.valueOf(json.get("message")).startsWith(messageStart))
                .findFirst().orElseThrow(() -> new AssertionError("No line starting with: " + messageStart));
    }

    private void assertEveryLineIsJson(List<String> lines) {
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            Map<String, Object> json = parse(line);
            for (String field : new String[] {"timestamp", "level", "service", "profile", "logger", "thread", "message"}) {
                assertThat(json).as("field " + field + " in " + line.substring(0, Math.min(200, line.length()))).containsKey(field);
            }
            assertThat(json.get("service")).isEqualTo("product-service");
            assertThat(String.valueOf(json.get("timestamp"))).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z");
        }
    }

    @Test
    void outsideDev_stdoutAndFile_areJsonLines_withEveryRequiredField(@TempDir Path logDir) throws Exception {
        Run run = runProbe(logDir, "prod");

        assertEveryLineIsJson(run.stdout());
        assertEveryLineIsJson(run.file());
        for (List<String> lines : List.of(run.stdout(), run.file())) {
            Map<String, Object> plain = probeLine(lines, "PROBE plain message");
            assertThat(plain.get("profile")).isEqualTo("prod");
            assertThat(plain.get("level")).isEqualTo("INFO");
            assertThat(plain.get("logger")).asString().endsWith("probe");
            assertThat(plain).containsEntry("userId", "probe-user").containsEntry("userEmail", "probe@example.com")
                    .containsEntry("userRole", "ADMIN").containsEntry("clientIp", "203.0.113.9")
                    .containsEntry("traceId", "0123456789abcdef0123456789abcdef").containsEntry("spanId", "0123456789abcdef");
        }
    }

    @Test
    void aMessageWithQuotesLineBreaksAndAVeryLongValue_isStillOneValidJsonLine(@TempDir Path logDir) throws Exception {
        Run run = runProbe(logDir, "prod");

        for (List<String> lines : List.of(run.stdout(), run.file())) {
            Map<String, Object> tricky = probeLine(lines, "PROBE tricky");
            assertThat(tricky.get("message")).isEqualTo(
                    "PROBE tricky message with \"quotes\", a back\\slash,\na line break, a tab\tand a value placeholder");
            Map<String, Object> longLine = probeLine(lines, "PROBE long value");
            assertThat(String.valueOf(longLine.get("message"))).hasSize("PROBE long value ".length() + LoggingProbeMain.LONG_VALUE.length());
        }
        // a line break in a message never becomes a second line
        assertThat(run.file()).allSatisfy(line -> assertThat(line).doesNotContain("\n"));
    }

    @Test
    void structuredArguments_areTopLevelFields_andTheEventFieldIsThere(@TempDir Path logDir) throws Exception {
        Run run = runProbe(logDir, "prod");

        Map<String, Object> access = probeLine(run.file(), "PROBE access-like line");
        assertThat(access.get("message")).isEqualTo("PROBE access-like line 200");
        assertThat(access).containsEntry("event", "http_request").containsEntry("httpStatus", 200)
                .containsEntry("durationMs", 12);

        Map<String, Object> audit = probeLine(run.file(), "PROBE audit");
        assertThat(audit).containsEntry("event", "probe_event").containsEntry("sku", "A\"B").containsEntry("delta", 3)
                .containsEntry("logger", "audit").containsEntry("level", "INFO");
        assertThat(audit.get("message")).isEqualTo("PROBE audit A\"B");
    }

    @Test
    void aStackTrace_isOneFieldOfOneLine(@TempDir Path logDir) throws Exception {
        Run run = runProbe(logDir, "prod");

        for (List<String> lines : List.of(run.stdout(), run.file())) {
            Map<String, Object> failure = probeLine(lines, "PROBE failure");
            assertThat(failure.get("level")).isEqualTo("ERROR");
            String stackTrace = String.valueOf(failure.get("stack_trace"));
            // the root cause comes first, so a long chain (a start-up failure) cannot cut it off
            assertThat(stackTrace).startsWith("java.lang.RuntimeException: root cause")
                    .contains("Wrapped by: java.lang.IllegalStateException: probe failure").contains("\n");
            assertThat(failure).doesNotContainKey("exception");
        }
        // the lines that follow a stack trace are not stack trace lines: every line of the file parsed as JSON above
        assertThat(run.file().stream().filter(line -> line.contains("stack_trace"))).hasSize(1);
    }

    @Test
    void theJsonFile_isWrittenInEveryProfile_andDevKeepsAReadableConsole(@TempDir Path logDir) throws Exception {
        Run run = runProbe(logDir, "dev");

        assertEveryLineIsJson(run.file());
        assertThat(probeLine(run.file(), "PROBE plain message").get("profile")).isEqualTo("dev");
        assertThat(run.file().stream().map(JsonLogOutputTest::parse)
                .filter(json -> String.valueOf(json.get("message")).equals("PROBE debug"))).hasSize(1); // dev logs debug

        String consoleLine = run.stdout().stream().filter(line -> line.contains("PROBE plain message")).findFirst().orElseThrow();
        assertThat(consoleLine).matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} INFO +\\[0123456789abcdef0123456789abcdef\\] "
                + "\\[user=probe-user ip=203\\.0\\.113\\.9\\] \\S+ - PROBE plain message");
        assertThat(run.stdout().stream().filter(line -> line.startsWith("{"))).as("no JSON on the dev console").isEmpty();
    }

    @Test
    void outsideDev_theLevelIsInfo_soDebugIsNotWritten(@TempDir Path logDir) throws Exception {
        Run run = runProbe(logDir, "prod");

        assertThat(run.file().stream().map(JsonLogOutputTest::parse).filter(json -> "PROBE debug".equals(json.get("message")))).isEmpty();
        assertThat(run.file().stream().map(JsonLogOutputTest::parse).filter(json -> "PROBE warn".equals(json.get("message")))).hasSize(1);
    }
}
