package com.microservices.cart.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.cart.model.CartItem;
import com.microservices.cart.repository.CartItemRepository;
import com.microservices.cart.support.FakeProductService;
import com.microservices.cart.support.TestTokens;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in test against a real PostgreSQL (the normal build skips it). Run it with:
 * <pre>
 * TEST_PG_URL=jdbc:postgresql://localhost:5432/microshop TEST_PG_USER=... TEST_PG_PASSWORD=... \
 *   mvn test -Dtest=PostgresSmokeTest
 * </pre>
 * It starts the whole service with the profile "postgres" and its own schema cart_svc_test, which is dropped
 * first, so the test can be run again and again. It checks that the PostgreSQL migration and Hibernate's schema
 * check work, and that the cart does over real HTTP what it does on H2. product-service is a fake HTTP server.
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres"})
class PostgresSmokeTest {

    private static final String SCHEMA = "cart_svc_test";

    /** Created only when the Spring context is built, so a skipped run starts nothing. */
    private static FakeProductService products;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        dropTestSchema();
        products = new FakeProductService();
        registry.add("DB_URL", () -> System.getenv("TEST_PG_URL"));
        registry.add("DB_USERNAME", () -> System.getenv("TEST_PG_USER"));
        registry.add("DB_PASSWORD", () -> System.getenv("TEST_PG_PASSWORD"));
        registry.add("DB_SCHEMA", () -> SCHEMA);
        registry.add("cart.product-service.url", products::baseUrl);
    }

    /** Plain JDBC, before Spring starts: the schema of an earlier run must not be there. */
    private static void dropTestSchema() {
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_PG_URL"),
                System.getenv("TEST_PG_USER"), System.getenv("TEST_PG_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not drop the schema " + SCHEMA + ": " + e.getMessage(), e);
        }
    }

    @AfterAll
    static void stopFake() {
        if (products != null) {
            products.stop();
        }
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
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private CartItemRepository repository;

    @Autowired
    private Environment environment;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // ---------------------------------------------------------------- start-up: Flyway and Hibernate

    @Test
    void theServiceStartedOnPostgres_inItsOwnSchema_withHibernateValidate() throws Exception {
        // the context is up, so Flyway ran and Hibernate's "validate" accepted the tables
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(entityManagerFactory.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect())
                .as("Hibernate finds the dialect itself").isInstanceOf(PostgreSQLDialect.class);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);

        // the health check (the load balancer uses it) sees the database
        HttpResponse<String> health = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/actuator/health")).GET().build());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(health.body()).at("/components/db/status").asText()).isEqualTo("UP");
    }

    @Test
    void everyMigrationScript_ranSuccessfully_inTheTestSchema() throws Exception {
        List<String> onDisk = new ArrayList<>();
        for (String folder : List.of("common", "postgresql")) {
            for (Resource script : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/" + folder + "/V*.sql")) {
                Matcher name = Pattern.compile("V(\\d+)__.*\\.sql").matcher(script.getFilename());
                assertThat(name.matches()).as(script.getFilename()).isTrue();
                onDisk.add(name.group(1));
            }
        }
        assertThat(onDisk).as("migration scripts for PostgreSQL").isNotEmpty();

        List<String> applied = jdbc.queryForList("SELECT version FROM " + SCHEMA
                + ".flyway_schema_history WHERE type = 'SQL' AND success = true ORDER BY installed_rank", String.class);
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM " + SCHEMA
                + ".flyway_schema_history WHERE success = false", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).containsExactlyInAnyOrderElementsOf(onDisk);
        assertThat(jdbc.queryForObject("SELECT to_regclass('" + SCHEMA + ".cart_items') IS NOT NULL", Boolean.class))
                .isTrue();
    }

    // ---------------------------------------------------------------- the cart over HTTP

    @Test
    void putGetUpdateDeleteAndClear_workOverHttp_inTheOrderTheItemsWereAdded() throws Exception {
        products.addProduct("PG-PHONE", "Pg Phone", "999.99");
        products.addProduct("PG-CASE", "Pg Case", "10.5");
        products.addProduct("PG-CABLE", "Pg Cable", "0.10");
        long userId = TestTokens.newUserId();
        String token = TestTokens.user(userId);

        assertThat(put(token, "PG-CASE", 1).statusCode()).isEqualTo(200);
        assertThat(put(token, "PG-PHONE", 2).statusCode()).isEqualTo(200);
        HttpResponse<String> third = put(token, "PG-CABLE", 3);
        assertThat(third.statusCode()).isEqualTo(200);

        // get: added order (by id), name, price, line total, count, total
        JsonNode cart = objectMapper.readTree(get(token).body());
        assertThat(cart.get("items")).hasSize(3);
        assertThat(skus(cart)).containsExactly("PG-CASE", "PG-PHONE", "PG-CABLE");
        assertThat(cart.get("items").get(1).get("name").asText()).isEqualTo("Pg Phone");
        assertThat(cart.get("items").get(1).get("lineTotal").decimalValue()).isEqualByComparingTo("1999.98");
        assertThat(cart.get("itemCount").asInt()).isEqualTo(6);
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("2010.78");
        assertThat(objectMapper.readTree(third.body())).as("PUT answers like GET").isEqualTo(cart);

        // update: sets the quantity, keeps the place in the list
        assertThat(put(token, "PG-CASE", 7).statusCode()).isEqualTo(200);
        cart = objectMapper.readTree(get(token).body());
        assertThat(skus(cart)).containsExactly("PG-CASE", "PG-PHONE", "PG-CABLE");
        assertThat(cart.get("items").get(0).get("quantity").asInt()).isEqualTo(7);
        assertThat(rows(userId)).isEqualTo(3);

        // delete one line (twice is fine)
        assertThat(delete(token, "/api/cart/items/PG-PHONE").statusCode()).isEqualTo(204);
        assertThat(delete(token, "/api/cart/items/PG-PHONE").statusCode()).isEqualTo(204);
        assertThat(skus(objectMapper.readTree(get(token).body()))).containsExactly("PG-CASE", "PG-CABLE");

        // another user is not touched by clear
        long otherUser = TestTokens.newUserId();
        assertThat(put(TestTokens.user(otherUser), "PG-CASE", 4).statusCode()).isEqualTo(200);

        assertThat(delete(token, "/api/cart").statusCode()).isEqualTo(204);
        assertThat(objectMapper.readTree(get(token).body()).get("items")).isEmpty();
        assertThat(rows(userId)).isZero();
        assertThat(rows(otherUser)).isEqualTo(1);

        // a removed item can be added again, and a bad quantity is a 400, not a database error
        assertThat(put(token, "PG-PHONE", 1).statusCode()).isEqualTo(200);
        assertThat(put(token, "PG-PHONE", 0).statusCode()).isEqualTo(400);
        assertThat(put(token, "PG-PHONE", 100).statusCode()).isEqualTo(400);
        assertThat(rows(userId)).isEqualTo(1);
    }

    @Test
    void aSku_isCaseSensitive_likeOnH2() throws Exception {
        products.addProduct("PG-Case-Test", "Upper", "1.00");
        products.addProduct("pg-case-test", "Lower", "2.00");
        String token = TestTokens.user(TestTokens.newUserId());

        assertThat(put(token, "PG-Case-Test", 1).statusCode()).isEqualTo(200);
        assertThat(put(token, "pg-case-test", 2).statusCode()).isEqualTo(200);

        JsonNode cart = objectMapper.readTree(get(token).body());
        assertThat(skus(cart)).containsExactly("PG-Case-Test", "pg-case-test");
        assertThat(delete(token, "/api/cart/items/pg-case-test").statusCode()).isEqualTo(204);
        assertThat(skus(objectMapper.readTree(get(token).body()))).containsExactly("PG-Case-Test");
    }

    // ---------------------------------------------------------------- the constraints of the table

    @Test
    void theDatabaseKeepsTheUniqueKey_theQuantityRange_andTheNotNullColumns() {
        long userId = TestTokens.newUserId();
        jdbc.update("INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'U-1', 1)", userId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'U-1', 2)", userId))
                .as("same user, same SKU").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.saveAndFlush(
                CartItem.builder().userId(userId).skuCode("U-1").quantity(3).build()))
                .as("the same, through Hibernate").isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'U-1', 2)", userId + 1_000_000);
        jdbc.update("INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'U-2', 99)", userId);

        for (int quantity : new int[] {0, 100, -1}) {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'U-3', ?)", userId, quantity))
                    .as("quantity " + quantity).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (NULL, 'U-4', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'S'||repeat('x', 50), 1)", userId))
                .as("sku_code is at most 50 characters").isInstanceOf(DataIntegrityViolationException.class);

        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'cart_items' ORDER BY ordinal_position", String.class, SCHEMA);
        assertThat(columns).containsExactly("id", "user_id", "sku_code", "quantity", "created_at", "updated_at");
    }

    @Test
    void timestamps_areTheSameMomentWhoeverWritesThem() throws Exception {
        // Hibernate writes UTC (hibernate.jdbc.time_zone), so the column default must be UTC too
        products.addProduct("PG-TIME", "Pg Time", "1.00");
        long userId = TestTokens.newUserId();
        assertThat(put(TestTokens.user(userId), "PG-TIME", 1).statusCode()).isEqualTo(200);
        jdbc.update("INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (?, 'RAW-TIME', 1)", userId);

        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        for (String sku : List.of("PG-TIME", "RAW-TIME")) {
            LocalDateTime created = jdbc.queryForObject(
                    "SELECT created_at FROM cart_items WHERE user_id = ? AND sku_code = ?",
                    LocalDateTime.class, userId, sku);
            assertThat(created).as("created_at of " + sku + " as stored").isBetween(utcNow.minusMinutes(2), utcNow.plusMinutes(2));
        }
        // an update moves updated_at only, and also in UTC
        LocalDateTime createdBefore = jdbc.queryForObject(
                "SELECT created_at FROM cart_items WHERE user_id = ? AND sku_code = 'PG-TIME'", LocalDateTime.class, userId);
        assertThat(put(TestTokens.user(userId), "PG-TIME", 2).statusCode()).isEqualTo(200);
        LocalDateTime createdAfter = jdbc.queryForObject(
                "SELECT created_at FROM cart_items WHERE user_id = ? AND sku_code = 'PG-TIME'", LocalDateTime.class, userId);
        LocalDateTime updated = jdbc.queryForObject(
                "SELECT updated_at FROM cart_items WHERE user_id = ? AND sku_code = 'PG-TIME'", LocalDateTime.class, userId);
        assertThat(createdAfter).isEqualTo(createdBefore);
        assertThat(updated).isAfterOrEqualTo(createdBefore).isBetween(utcNow.minusMinutes(2), utcNow.plusMinutes(2));
        // read back through Hibernate it is the local time of this JVM again
        CartItem viaHibernate = repository.findByUserIdAndSkuCode(userId, "PG-TIME").orElseThrow();
        LocalDateTime localNow = LocalDateTime.now();
        assertThat(viaHibernate.getCreatedAt()).isBetween(localNow.minusMinutes(2), localNow.plusMinutes(2));
        assertThat(viaHibernate.getUpdatedAt()).isBetween(localNow.minusMinutes(2), localNow.plusMinutes(2));
    }

    // ---------------------------------------------------------------- parallel requests

    @Test
    void parallelPutsOfTheSameLine_neverGive500_andNeverLeaveTwoRows() throws Exception {
        products.addProduct("PG-RACE", "Pg Race", "5.00");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                long userId = TestTokens.newUserId();
                String token = TestTokens.user(userId);
                // first wave: the line does not exist yet (the insert race), second wave: it exists (the update race)
                for (int wave = 0; wave < 2; wave++) {
                    int currentWave = wave;
                    List<Integer> sent = new ArrayList<>();
                    List<Integer> statuses = runTogether(pool, threads, i -> {
                        int quantity = (currentWave * threads + i) % 99 + 1;
                        synchronized (sent) {
                            sent.add(quantity);
                        }
                        return put(token, "PG-RACE", quantity).statusCode();
                    });
                    assertThat(statuses).as("round " + round + " wave " + wave).containsOnly(200);
                    assertThat(rows(userId)).as("one row only (unique key)").isEqualTo(1);
                    Integer stored = jdbc.queryForObject(
                            "SELECT quantity FROM cart_items WHERE user_id = ?", Integer.class, userId);
                    assertThat(stored).as("the quantity of one of the requests").isIn(sent);
                }
                assertThat(objectMapper.readTree(get(token).body()).get("items")).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelPutsOfDifferentLines_allArrive() throws Exception {
        int lines = 8;
        for (int i = 0; i < lines; i++) {
            products.addProduct("PG-LINE-" + i, "Line " + i, "1.00");
        }
        long userId = TestTokens.newUserId();
        String token = TestTokens.user(userId);
        ExecutorService pool = Executors.newFixedThreadPool(lines);
        try {
            List<Integer> statuses = runTogether(pool, lines, i -> put(token, "PG-LINE-" + i, i + 1).statusCode());
            assertThat(statuses).containsOnly(200);
        } finally {
            pool.shutdownNow();
        }
        JsonNode cart = objectMapper.readTree(get(token).body());
        assertThat(cart.get("items")).hasSize(lines);
        assertThat(cart.get("itemCount").asInt()).isEqualTo(36);
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("36.00");
    }

    // ---------------------------------------------------------------- money

    @Test
    void money_isExact_withTheSameScaleAndRoundingAsOnH2() throws Exception {
        products.addProduct("PG-M-PHONE", "Phone", "999.99");
        products.addProduct("PG-M-CASE", "Case", "10.5");
        products.addProduct("PG-M-PENNY", "Penny", "0.01");
        products.addProduct("PG-M-CABLE", "Cable", "0.10");
        products.addProduct("PG-M-BIG", "Big", "9999999999.99");
        products.addProduct("PG-M-ROUND", "Round", "1.005");
        products.addProduct("PG-M-ROUND2", "Round 2", "2.675");

        String token = TestTokens.user(TestTokens.newUserId());
        put(token, "PG-M-PHONE", 2);
        put(token, "PG-M-CASE", 2);
        String body = get(token).body();
        assertThat(body).contains("\"unitPrice\":999.99").contains("\"lineTotal\":1999.98")
                .contains("\"unitPrice\":10.50").contains("\"lineTotal\":21.00").contains("\"total\":2020.98");

        String smallToken = TestTokens.user(TestTokens.newUserId());
        put(smallToken, "PG-M-PENNY", 99);
        put(smallToken, "PG-M-CABLE", 99);
        assertThat(objectMapper.readTree(get(smallToken).body()).get("total").decimalValue())
                .isEqualTo(new java.math.BigDecimal("10.89"));

        String bigToken = TestTokens.user(TestTokens.newUserId());
        put(bigToken, "PG-M-BIG", 99);
        assertThat(objectMapper.readTree(get(bigToken).body()).get("total").decimalValue())
                .isEqualByComparingTo("989999999999.01");

        // a price with three decimals is rounded half up to cents, and the line is quantity times the rounded price
        String roundToken = TestTokens.user(TestTokens.newUserId());
        put(roundToken, "PG-M-ROUND", 3);
        put(roundToken, "PG-M-ROUND2", 3);
        JsonNode cart = objectMapper.readTree(get(roundToken).body());
        assertThat(cart.get("items").get(0).get("unitPrice").decimalValue()).isEqualTo(new java.math.BigDecimal("1.01"));
        assertThat(cart.get("items").get(0).get("lineTotal").decimalValue()).isEqualTo(new java.math.BigDecimal("3.03"));
        assertThat(cart.get("items").get(1).get("unitPrice").decimalValue()).isEqualTo(new java.math.BigDecimal("2.68"));
        assertThat(cart.get("total").decimalValue()).isEqualTo(new java.math.BigDecimal("11.07"));
    }

    // ---------------------------------------------------------------- helpers

    private List<Integer> runTogether(ExecutorService pool, int count, Call call) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            tasks.add(() -> {
                go.await();
                return call.run(index);
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        go.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private interface Call {
        int run(int index) throws Exception;
    }

    private int rows(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", Integer.class, userId);
    }

    private static List<String> skus(JsonNode cart) {
        List<String> skus = new ArrayList<>();
        cart.get("items").forEach(line -> skus.add(line.get("sku").asText()));
        return skus;
    }

    private HttpRequest.Builder request(String token, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", TestTokens.bearer(token));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String token, String sku, int quantity) throws Exception {
        return send(request(token, "/api/cart/items/" + sku).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"quantity\":" + quantity + "}")).build());
    }

    private HttpResponse<String> get(String token) throws Exception {
        return send(request(token, "/api/cart").GET().build());
    }

    private HttpResponse<String> delete(String token, String path) throws Exception {
        return send(request(token, path).DELETE().build());
    }
}
