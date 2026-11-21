package com.microservices.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.service.AdminSeeder;
import com.microservices.user.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole service on a real PostgreSQL (the "postgres" profile) and checks what H2 cannot show.
 * It is skipped by the normal build. To run it:
 *
 *   TEST_PG_URL=jdbc:postgresql://localhost:5432/microshop TEST_PG_USER=... TEST_PG_PASSWORD=... \
 *     mvn test -Dtest=PostgresSmokeTest
 *
 * It only uses the schema user_svc_test, which is dropped before the service starts, so it can be run again.
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres"})
class PostgresSmokeTest {

    private static final String SCHEMA = "user_svc_test";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ADMIN_EMAIL = "test.admin@example.com";
    private static final String ADMIN_PASSWORD = "test-admin-password";

    /** Runs before the Spring context is built: a clean schema, then the settings the "postgres" profile needs. */
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) throws SQLException {
        try (Connection connection = DriverManager.getConnection(env("TEST_PG_URL"), env("TEST_PG_USER"),
                env("TEST_PG_PASSWORD")); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
        registry.add("DB_URL", () -> env("TEST_PG_URL"));
        registry.add("DB_USERNAME", () -> env("TEST_PG_USER"));
        registry.add("DB_PASSWORD", () -> env("TEST_PG_PASSWORD"));
        registry.add("DB_SCHEMA", () -> SCHEMA);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("The environment variable " + name + " is not set");
        }
        return value;
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private AdminSeeder adminSeeder;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    private final HttpClient client = HttpClient.newHttpClient();

    // ---------------------------------------------------------------- the database and the migrations

    @Test
    void migrationsSucceeded_onPostgres_inTheOwnSchema_andHibernateValidatePassed() throws Exception {
        // The context is running, so Hibernate "validate" has accepted the tables Flyway made.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);

        assertThat(jdbc.queryForObject(
                "select count(*) from " + SCHEMA + ".flyway_schema_history where success = false", Integer.class))
                .isZero();
        // one history row for each script in common/ and postgresql/ (and none for h2/)
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        // (classpath*: because common/ does not exist while this service has no script that runs on both databases)
        int scripts = resolver.getResources("classpath*:db/migration/common/*.sql").length
                + resolver.getResources("classpath*:db/migration/postgresql/*.sql").length;
        assertThat(scripts).isPositive();
        assertThat(jdbc.queryForObject(
                "select count(*) from " + SCHEMA + ".flyway_schema_history where type = 'SQL'", Integer.class))
                .isEqualTo(scripts);

        assertThat(jdbc.queryForObject("select to_regclass('" + SCHEMA + ".users') is not null", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("select is_identity from information_schema.columns "
                + "where table_schema = ? and table_name = 'users' and column_name = 'id'", String.class, SCHEMA))
                .isEqualTo("YES");
    }

    // ---------------------------------------------------------------- register and log in

    @Test
    void registerAndLogin_overHttp_giveATokenThatDecodesAndVerifies() throws Exception {
        String email = "Smoke-" + UUID.randomUUID() + "@Example.com";
        HttpResponse<String> registered = register(email);
        assertThat(registered.statusCode()).isEqualTo(201);
        JsonNode user = objectMapper.readTree(registered.body());
        long id = user.get("id").asLong();
        assertThat(user.get("email").asText()).isEqualTo(email.toLowerCase());

        JsonNode login = objectMapper.readTree(login(email, PASSWORD).body());
        String token = login.get("token").asText();
        assertThat(login.get("role").asText()).isEqualTo("USER");

        // The token verifies with the shared secret and carries the contract's claims
        Jwt jwt = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build().decode(token);
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(id));
        assertThat(jwt.<String>getClaim("email")).isEqualTo(email.toLowerCase());
        assertThat(jwt.<String>getClaim("role")).isEqualTo("USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(8));

        HttpResponse<String> me = get("/api/users/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(me.body()).get("email").asText()).isEqualTo(email.toLowerCase());

        assertThat(login(email, "wrong-password-123").statusCode()).isEqualTo(401);

        // Only a hash is stored
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, id))
                .startsWith("$2").isNotEqualTo(PASSWORD);

        // Times: the service writes UTC to the database and shows the same moment back (this Mac is not on UTC)
        LocalDateTime inDatabase = jdbc.queryForObject("select created_at from users where id = ?",
                LocalDateTime.class, id);
        assertThat(Duration.between(inDatabase, LocalDateTime.now(ZoneOffset.UTC)).abs())
                .isLessThan(Duration.ofMinutes(2));
        JsonNode listed = objectMapper.readTree(get("/api/users?q=" + encode(email.toLowerCase()),
                adminToken()).body()).get("content").get(0);
        assertThat(Duration.between(LocalDateTime.parse(listed.get("createdAt").asText()), LocalDateTime.now()).abs())
                .isLessThan(Duration.ofMinutes(2));
    }

    @Test
    void registeringTheSameEmailTwice_isTheNormal409_not500() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        assertThat(register(email).statusCode()).isEqualTo(201);

        HttpResponse<String> again = register(email);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(objectMapper.readTree(again.body()).get("title").asText()).isEqualTo("Duplicate Email");

        // upper case letters are the same account
        assertThat(register(email.toUpperCase()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void sameEmailAtTheSameMoment_oneRequestWins_theOthersGet409_andTheConnectionsStayUsable() throws Exception {
        // The password is hashed after the "is it taken" check and before the insert, so all the requests pass
        // the check and the unique key in the database has to decide. PostgreSQL then aborts the transaction.
        try (LogCapture logs = LogCapture.start()) {
            for (int round = 0; round < 3; round++) {
                String email = "race-" + UUID.randomUUID() + "@example.com";
                List<Integer> statuses = inParallel(4, () -> register(email).statusCode());

                assertThat(statuses).as("statuses of round " + round).containsOnly(201, 409);
                assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                        .isEqualTo(1);
            }
            // PostgreSQL really did refuse an insert (so the 409 came from the unique key, not from the check)
            assertThat(logs.allText()).contains("23505");
        }

        // The pool (5 connections) is still healthy after the aborted transactions
        String email = "after-race-" + UUID.randomUUID() + "@example.com";
        assertThat(register(email).statusCode()).isEqualTo(201);
        for (int i = 0; i < 12; i++) {
            assertThat(login(email, PASSWORD).statusCode()).isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- the admin list

    @Test
    void adminList_pagingAndSearch_work_andTheSearchIsCaseInsensitive() throws Exception {
        String tag = "lt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(objectMapper.readTree(register(tag + "-" + i + "@Example.com").body()).get("id").asLong());
        }
        String token = adminToken();

        JsonNode all = json(get("/api/users?q=" + tag, token));
        assertThat(all.get("totalElements").asLong()).isEqualTo(5);
        assertThat(all.get("size").asInt()).isEqualTo(20);
        List<Long> seen = new ArrayList<>();
        all.get("content").forEach(user -> seen.add(user.get("id").asLong()));
        assertThat(seen).containsExactly(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0));
        assertThat(all.get("content").get(0).get("email").asText()).isEqualTo(tag + "-4@example.com");

        JsonNode second = json(get("/api/users?q=" + tag + "&size=2&page=1", token));
        assertThat(second.get("totalElements").asLong()).isEqualTo(5);
        assertThat(second.get("totalPages").asInt()).isEqualTo(3);
        assertThat(second.get("content")).hasSize(2);
        assertThat(second.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(second.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));
        assertThat(json(get("/api/users?q=" + tag + "&size=2&page=2", token)).get("last").asBoolean()).isTrue();

        // the search ignores case (the stored e-mail is lower case, the search text is not)
        assertThat(total(tag.toUpperCase(), token)).isEqualTo(5);
        assertThat(total(tag.substring(0, 6).toUpperCase() + tag.substring(6), token)).isEqualTo(5);
        assertThat(total(tag + "-3@EXAMPLE.COM", token)).isEqualTo(1);
        assertThat(total("no-such-person-" + tag, token)).isZero();

        // % _ and \ are plain characters, and a quote does no harm
        for (String text : new String[] {"%", "_", "%%", "a%", "\\", "a\\b", "'", "x' or '1'='1"}) {
            assertThat(total(text, token)).as("q=" + text).isZero();
        }

        // an empty search is no filter; the hash is never shown
        assertThat(total("", token)).isEqualTo(json(get("/api/users", token)).get("totalElements").asLong());
        assertThat(get("/api/users?size=100", token).body()).doesNotContain("$2a$").doesNotContain("password");
    }

    // ---------------------------------------------------------------- the admin seeder

    @Test
    void startingTheApplicationASecondTime_onTheSameSchema_doesNotDuplicateTheAdminOrFail() throws Exception {
        int historyBefore = jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class);
        String hashBefore = jdbc.queryForObject("select password_hash from users where email = ?", String.class,
                ADMIN_EMAIL);
        assertThat(adminCount()).isEqualTo(1);

        try (ConfigurableApplicationContext second = new SpringApplication(UserServiceApplication.class).run(
                "--spring.profiles.active=test,postgres", "--server.port=0",
                "--DB_URL=" + env("TEST_PG_URL"), "--DB_USERNAME=" + env("TEST_PG_USER"),
                "--DB_PASSWORD=" + env("TEST_PG_PASSWORD"), "--DB_SCHEMA=" + SCHEMA)) {
            assertThat(second.isRunning()).isTrue();
        }

        assertThat(adminCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from users where email = ?", String.class,
                ADMIN_EMAIL)).isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class))
                .isEqualTo(historyBefore);
    }

    @Test
    void twoSeedersAtTheSameMoment_giveExactlyOneAdmin_andNoException() throws Exception {
        jdbc.update("delete from users where email = ?", ADMIN_EMAIL);
        assertThat(adminCount()).isZero();

        try (LogCapture logs = LogCapture.start()) {
            // run() returns nothing: an exception that escaped would show up in the future
            inParallel(2, () -> {
                adminSeeder.run(null);
                return 0;
            });

            assertThat(adminCount()).isEqualTo(1);
            // one of the two lost the race at the unique key and took it as "created by another instance"
            assertThat(logs.allText()).contains("Created the admin account")
                    .contains("Admin account was created by another instance at the same time");
        }
        assertThat(login(ADMIN_EMAIL, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    private long adminCount() {
        return jdbc.queryForObject("select count(*) from users where email = ?", Long.class, ADMIN_EMAIL);
    }

    /** Runs the task on this many threads, all released at the same moment, and returns what each returned. */
    private <T> List<T> inParallel(int threads, Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder url(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(url(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(url(path).header("Authorization", "Bearer " + token).GET());
    }

    private HttpResponse<String> register(String email) throws Exception {
        return postJson("/api/users/register", credentials(email, PASSWORD));
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return postJson("/api/users/login", credentials(email, password));
    }

    private String credentials(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
    }

    private String adminToken() throws Exception {
        HttpResponse<String> login = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        return objectMapper.readTree(login.body()).get("token").asText();
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private long total(String search, String token) throws Exception {
        return json(get("/api/users?q=" + encode(search), token)).get("totalElements").asLong();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
