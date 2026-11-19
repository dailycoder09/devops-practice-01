package com.microservices.cart.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test 4: the real logback configuration, in a real JVM, with a real stdout and a real log file.
 * Outside the dev profile stdout is JSON; in dev stdout is readable text; the file is always JSON.
 */
class LogOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "a-real-random-secret-with-more-than-32-bytes-in-it";
    private static final String URL_PASSWORD = "p4ssw0rd-in-the-url";

    @TempDir
    Path folder;

    private record Run(List<String> stdout, List<String> file, String stderr) {
    }

    private Run runProbe(String profile) throws Exception {
        Path logDir = folder.resolve("logs-" + profile);
        Path out = folder.resolve("stdout-" + profile + ".txt");
        Path err = folder.resolve("stderr-" + profile + ".txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The class path of this test, without devtools (the packaged jar has none): devtools would restart the
        // application in a second class loader and writes a line of its own before the logging is set up.
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")).split(File.pathSeparator))
                .filter(entry -> !entry.contains("spring-boot-devtools"))
                .collect(Collectors.joining(File.pathSeparator));

        List<String> command = new ArrayList<>(List.of(java, "-Xmx300m",
                "-DLOG_PATH=" + logDir, "-cp", classpath, LogProbeMain.class.getName(),
                "--spring.profiles.active=" + profile, "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=jdbc:h2:mem:probe-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "--app.security.jwt.secret=" + SECRET,
                "--cart.product-service.url=http://someone:" + URL_PASSWORD + "@localhost:9/some/path?token=abc"));
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("The probe did not finish in time. Its output so far:\n"
                    + Files.readString(out, StandardCharsets.UTF_8));
        }
        String stderr = Files.readString(err, StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("exit code of the probe. stderr:\n" + stderr).isZero();

        Path jsonFile = logDir.resolve("cart-service.json.log");
        assertThat(jsonFile).as("the rolling JSON file").exists();
        return new Run(Files.readAllLines(out, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList(),
                Files.readAllLines(jsonFile, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList(),
                stderr);
    }

    private static List<JsonNode> parseAll(List<String> lines, String where) {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : lines) {
            try {
                JsonNode node = MAPPER.readTree(line);
                assertThat(node.isObject()).as("a JSON object in " + where + ": " + shorten(line)).isTrue();
                nodes.add(node);
            } catch (Exception e) {
                throw new AssertionError("A line in " + where + " is not JSON: " + shorten(line), e);
            }
        }
        return nodes;
    }

    private static String shorten(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static JsonNode byMessage(List<JsonNode> nodes, String message) {
        return nodes.stream().filter(n -> message.equals(n.path("message").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("No line with the message " + shorten(message)));
    }

    private static JsonNode byEvent(List<JsonNode> nodes, String event) {
        return nodes.stream().filter(n -> event.equals(n.path("event").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("No line with the event " + event));
    }

    private static void assertRequiredFields(List<JsonNode> nodes, String profile) {
        assertThat(nodes).isNotEmpty();
        for (JsonNode node : nodes) {
            String line = shorten(node.toString());
            assertThat(node.path("timestamp").asText()).as("timestamp in " + line)
                    .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
            assertThat(node.path("level").asText()).as("level in " + line).isNotBlank();
            assertThat(node.path("service").asText()).as("service in " + line).isEqualTo("cart-service");
            assertThat(node.path("profile").asText()).as("profile in " + line).isEqualTo(profile);
            assertThat(node.path("logger").asText()).as("logger in " + line).isNotBlank();
            assertThat(node.path("thread").asText()).as("thread in " + line).isNotBlank();
            assertThat(node.has("message")).as("message in " + line).isTrue();
        }
    }

    private static void assertTheAwkwardLines(List<JsonNode> nodes) {
        JsonNode tricky = byMessage(nodes, LogProbeMain.TRICKY);
        assertThat(tricky.get("level").asText()).isEqualTo("INFO");
        assertThat(tricky.get("userId").asText()).isEqualTo("42");
        assertThat(tricky.get("userEmail").asText()).isEqualTo("probe@example.com");
        assertThat(tricky.get("userRole").asText()).isEqualTo("USER");
        assertThat(tricky.get("clientIp").asText()).isEqualTo("198.51.100.1");

        JsonNode longLine = byMessage(nodes, LogProbeMain.LONG);
        assertThat(longLine.get("message").asText()).hasSize(50_000);

        JsonNode error = byMessage(nodes, "an error with a cause");
        assertThat(error.get("level").asText()).isEqualTo("ERROR");
        assertThat(error.get("stack_trace").isTextual()).isTrue();
        assertThat(error.get("stack_trace").asText()).contains("IllegalStateException: outer failure")
                .contains("inner cause").contains("\n");

        JsonNode audit = byEvent(nodes, "probe_event");
        assertThat(audit.get("logger").asText()).isEqualTo("audit");
        assertThat(audit.get("sku").asText()).isEqualTo("A\"B\nC");

        JsonNode access = byEvent(nodes, "http_request");
        assertThat(access.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(access.get("httpPath").asText()).isEqualTo("/actuator/info");
        assertThat(access.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(access.get("clientIp").asText()).isEqualTo("203.0.113.7");
        assertThat(access.get("userId").asText()).isEqualTo("anonymous");
        assertThat(access.get("traceId").asText()).matches("[0-9a-f]{16,32}");
        assertThat(access.get("spanId").asText()).matches("[0-9a-f]{16}");
        assertThat(access.get("logger").asText()).endsWith("RequestLoggingFilter")
                .isNotEqualTo(RequestLoggingFilter.class.getName());

        JsonNode started = byEvent(nodes, "service_started");
        assertThat(started.get("jwtSecretConfigured").asBoolean()).isTrue();
        assertThat(started.get("port").asInt()).isPositive();
        assertThat(started.get("version").asText()).isNotBlank();
        assertThat(started.get("database").asText()).containsIgnoringCase("H2");
        assertThat(started.get("downstream").get("product-service").asText()).isEqualTo("http://localhost:9");
    }

    @Test
    void outsideDev_stdoutAndTheFileAreJson_lineByLine() throws Exception {
        Run run = runProbe("prod");

        List<JsonNode> stdout = parseAll(run.stdout(), "stdout");
        List<JsonNode> file = parseAll(run.file(), "the JSON file");
        assertRequiredFields(stdout, "prod");
        assertRequiredFields(file, "prod");
        assertTheAwkwardLines(stdout);
        assertTheAwkwardLines(file);
        assertThat(String.join("\n", run.stdout()) + String.join("\n", run.file()))
                .doesNotContain(SECRET).doesNotContain(URL_PASSWORD).doesNotContain("token=abc");
    }

    @Test
    void inDev_stdoutIsReadableText_andTheFileIsStillJson() throws Exception {
        Run run = runProbe("dev");

        List<JsonNode> file = parseAll(run.file(), "the JSON file");
        assertRequiredFields(file, "dev");
        assertTheAwkwardLines(file);

        assertThat(run.stdout()).isNotEmpty();
        assertThat(run.stdout().get(0)).doesNotStartWith("{");
        String text = String.join("\n", run.stdout());
        assertThat(text).contains("[user=42 ip=198.51.100.1]").contains("quote \" backslash");
        assertThat(text).containsPattern("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} INFO +\\[[0-9a-f]{16,32}\\] \\[user=anonymous ip=203\\.0\\.113\\.7\\]");
        assertThat(text).doesNotContain(SECRET).doesNotContain(URL_PASSWORD);
    }
}
