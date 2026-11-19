package com.microservices.product.logging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.product.ProductServiceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real application, started in its own JVM with the production profile (random port, a throw-away database in
 * a temporary folder, the working directory is a temporary folder, so nothing of a running service is touched).
 *
 * - Without a JWT secret, or with the development placeholder, it refuses to start, and the reason is in the
 *   structured output (and never the placeholder value).
 * - With a good secret it starts, writes one service_started line, and the secret is nowhere in the output.
 */
class StartupBehaviourTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLACEHOLDER = "dev-only-placeholder-secret-do-not-use-in-aws-0123456789";
    private static final String GOOD_SECRET = "a-startup-test-secret-0123456789-abcdefghijklmnop";

    private static final class Child {
        final Process process;
        final List<String> stdout = new CopyOnWriteArrayList<>();
        final List<String> stderr = new CopyOnWriteArrayList<>();

        Child(Process process) {
            this.process = process;
            pump(process.getInputStream(), stdout);
            pump(process.getErrorStream(), stderr);
        }

        private static void pump(java.io.InputStream stream, List<String> target) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        target.add(line);
                    }
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        boolean waitUntil(Predicate<List<String>> condition, long seconds) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < end) {
                if (condition.test(stdout)) {
                    return true;
                }
                if (!process.isAlive()) {
                    Thread.sleep(300); // let the pumps drain
                    return condition.test(stdout);
                }
                Thread.sleep(200);
            }
            return false;
        }
    }

    /** The test classpath without Spring Boot devtools: a deployed jar does not contain it, and it prints plain text. */
    private static String classpathWithoutDevtools() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("spring-boot-devtools"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static Child start(Path folder, String secret) throws IOException {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(javaBinary, "-Xlog:disable",
                "-DLOG_PATH=" + folder.resolve("logs"),
                // the prod profile reads DB_URL; a throw-away database in the temporary folder
                "-DDB_URL=jdbc:h2:file:" + folder.resolve("db"),
                // no seed data and no schema check: these tests are about the start-up and the log, not the database
                "-Dspring.flyway.enabled=false", "-Dspring.jpa.hibernate.ddl-auto=none",
                "-DCLOUD_METADATA_ENABLED=false", "-cp", classpathWithoutDevtools(),
                ProductServiceApplication.class.getName(), "--spring.profiles.active=prod", "--server.port=0"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile());
        builder.environment().remove("JWT_SECRET");
        builder.environment().remove("SPRING_PROFILES_ACTIVE");
        if (secret != null) {
            builder.environment().put("JWT_SECRET", secret);
        }
        Process process = builder.start();
        process.getOutputStream().close();
        return new Child(process);
    }

    private static Map<String, Object> parse(String line) {
        try {
            return MAPPER.readValue(line, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new AssertionError("Not valid JSON: " + line.substring(0, Math.min(300, line.length())), ex);
        }
    }

    private static boolean mentions(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void withoutAJwtSecret_itRefusesToStart_andTheReasonIsInTheJsonOutput(@TempDir Path folder) throws Exception {
        Child child = start(folder, null);
        try {
            assertThat(child.waitUntil(lines -> mentions(lines, "JWT secret is missing"), 120)).as("the refusal is logged").isTrue();
            assertThat(child.process.waitFor(60, TimeUnit.SECONDS)).as("the process ends").isTrue();

            assertThat(child.process.exitValue()).isNotZero();
            child.stdout.forEach(line -> assertThat(parse(line)).containsEntry("service", "product-service")
                    .containsEntry("profile", "prod"));
            assertThat(child.stdout.stream().map(StartupBehaviourTest::parse)
                    .filter(json -> "ERROR".equals(json.get("level")) && String.valueOf(json.get("stack_trace")).contains("JWT_SECRET")))
                    .as("an ERROR line that names JWT_SECRET").isNotEmpty();
            assertThat(mentions(child.stdout, "service_started")).isFalse();
        } finally {
            child.process.destroyForcibly();
        }
    }

    @Test
    void withTheDevPlaceholderOutsideDev_itRefusesToStart_andNeverPrintsThePlaceholder(@TempDir Path folder) throws Exception {
        Child child = start(folder, PLACEHOLDER);
        try {
            assertThat(child.waitUntil(lines -> mentions(lines, "development placeholder"), 120)).as("the refusal is logged").isTrue();
            assertThat(child.process.waitFor(60, TimeUnit.SECONDS)).as("the process ends").isTrue();

            assertThat(child.process.exitValue()).isNotZero();
            child.stdout.forEach(StartupBehaviourTest::parse);
            assertThat(mentions(child.stdout, PLACEHOLDER)).as("the placeholder value in stdout").isFalse();
            assertThat(mentions(child.stderr, PLACEHOLDER)).as("the placeholder value in stderr").isFalse();
        } finally {
            child.process.destroyForcibly();
        }
    }

    @Test
    void withAGoodSecret_itStarts_writesOneStartedLine_andNeverPrintsTheSecret(@TempDir Path folder) throws Exception {
        Child child = start(folder, GOOD_SECRET);
        try {
            assertThat(child.waitUntil(lines -> mentions(lines, "service_started"), 240)).as("the started line").isTrue();

            var started = child.stdout.stream().filter(line -> line.contains("service_started")).map(StartupBehaviourTest::parse).toList();
            assertThat(started).hasSize(1);
            Map<String, Object> line = started.get(0);
            assertThat(line).containsEntry("service", "product-service").containsEntry("profile", "prod")
                    .containsEntry("event", "service_started").containsEntry("jwtSecretConfigured", true)
                    .containsEntry("database", "H2").containsEntry("level", "INFO");
            assertThat(line.get("profiles")).isEqualTo("prod");
            assertThat(((Number) line.get("port")).intValue()).isPositive();
            assertThat(String.valueOf(line.get("downstream"))).contains("inventory-service=http://localhost:8082");

            // a real request over a real socket, through the real container and filters
            int port = ((Number) line.get("port")).intValue();
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> refused = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/products"))
                    .header("X-Forwarded-For", "203.0.113.55")
                    .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(refused.statusCode()).isEqualTo(401);
            assertThat(refused.headers().firstValue("X-Trace-Id")).contains(traceId);
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(child.waitUntil(lines -> mentions(lines, "http_request"), 30)).as("the access line").isTrue();
            var access = child.stdout.stream().filter(l -> l.contains("http_request")).map(StartupBehaviourTest::parse).toList();
            assertThat(access).as("the health check is not an INFO line outside dev").hasSize(1);
            assertThat(access.get(0)).containsEntry("httpMethod", "POST").containsEntry("httpPath", "/api/products")
                    .containsEntry("httpStatus", 401).containsEntry("level", "WARN").containsEntry("userId", "anonymous")
                    .containsEntry("clientIp", "203.0.113.55").containsEntry("traceId", traceId);
            assertThat(mentions(child.stdout, "auth_rejected")).isTrue();

            child.stdout.forEach(StartupBehaviourTest::parse); // every line of the real start-up is JSON
            assertThat(mentions(child.stdout, GOOD_SECRET)).as("the secret in stdout").isFalse();
            assertThat(mentions(child.stderr, GOOD_SECRET)).as("the secret in stderr").isFalse();
        } finally {
            child.process.destroy();
            if (!child.process.waitFor(30, TimeUnit.SECONDS)) {
                child.process.destroyForcibly();
            }
        }
    }
}
