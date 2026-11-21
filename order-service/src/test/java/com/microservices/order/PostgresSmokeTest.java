package com.microservices.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.support.FakeShop;
import com.microservices.order.support.FakeShop.Behavior;
import com.microservices.order.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test against a real PostgreSQL server. The normal build skips it.
 *
 * Run it with TEST_PG_URL (jdbc:postgresql://host:port/database), TEST_PG_USER and TEST_PG_PASSWORD set:
 * <pre>
 * TEST_PG_URL=jdbc:postgresql://localhost:5432/microshop TEST_PG_USER=... TEST_PG_PASSWORD=... mvn test -Dtest=PostgresSmokeTest
 * </pre>
 * The whole service starts with the profile "postgres" (the same file as on AWS) and its own schema order_svc_test.
 * That schema is dropped before the context starts, so the test can be run again and again.
 * The cart, product and inventory services are the fakes of the other tests.
 *
 * Starting the context already proves two things: Flyway ran every PostgreSQL migration, and Hibernate's
 * schema validation accepted the tables. The tests then check the parts where H2 and PostgreSQL differ.
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres"})
class PostgresSmokeTest {

    private static final String SCHEMA = "order_svc_test";

    /** Shown in pg_stat_activity, so the test can look at its own connections only. */
    private static final String APPLICATION_NAME = "order-service-pg-smoke";

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 11, 1, 10, 0, 0);

    private static final AtomicLong NEXT_USER_ID = new AtomicLong(7000);

    private static final int ADMIN_ID = 9_001;

    /** Looked up when needed, so a skipped test class does not start the fake services. */
    private static FakeShop shop() {
        return FakeShop.get();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        // Runs before the Spring context is created: start from nothing.
        try (Connection connection = DriverManager.getConnection(
                System.getenv("TEST_PG_URL"), System.getenv("TEST_PG_USER"), System.getenv("TEST_PG_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
        String url = System.getenv("TEST_PG_URL");
        registry.add("DB_URL", () -> url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + APPLICATION_NAME);
        registry.add("DB_USERNAME", () -> System.getenv("TEST_PG_USER"));
        registry.add("DB_PASSWORD", () -> System.getenv("TEST_PG_PASSWORD"));
        registry.add("DB_SCHEMA", () -> SCHEMA);
        registry.add("order.cart-service.url", () -> shop().cartServer.baseUrl());
        registry.add("order.product-service.url", () -> shop().productServer.baseUrl());
        registry.add("order.inventory-service.url", () -> shop().inventoryServer.baseUrl());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    private long userId;
    private String userToken;
    private final String adminToken = JwtTestSupport.adminToken(ADMIN_ID);

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");

        shop().reset();
        shop().addProduct("SKU-A", "Phone A", "100.00");
        shop().addStock("SKU-A", 10);
        shop().addProduct("SKU-B", "Case B", "20.50");
        shop().addStock("SKU-B", 5);
        shop().addProduct("SKU-C", "Cable C", "5.00");
        shop().addStock("SKU-C", 8);

        userId = NEXT_USER_ID.incrementAndGet();
        userToken = JwtTestSupport.userToken(userId);
    }

    // ================================================================== migrations and schema

    @Test
    void everyMigrationRan_inTheOrderSchema_andNowhereElse() throws Exception {
        // Every row of the history succeeded, and the versions are exactly those of the PostgreSQL folders, in order.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + SCHEMA + ".flyway_schema_history WHERE success = false",
                Long.class)).isZero();
        List<String> applied = jdbc.queryForList("SELECT version FROM " + SCHEMA
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", String.class);
        assertThat(applied).isNotEmpty().containsExactlyElementsOf(migrationVersionsInTheJar());

        // The tables are in order_svc_test (the connection's schema), not in public.
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        assertThat(tablesIn(SCHEMA)).contains("orders", "order_items", "flyway_schema_history");
        assertThat(tablesIn("public")).doesNotContain("orders", "order_items", "flyway_schema_history");
    }

    @Test
    void rowsInsertedWithoutATime_getUtc_whateverTheTimeZoneOfTheConnection() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getenv("TEST_PG_URL"), System.getenv("TEST_PG_USER"), System.getenv("TEST_PG_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'Asia/Kolkata'");
            connection.setSchema(SCHEMA);
            try (ResultSet row = statement.executeQuery("INSERT INTO orders (user_id, status, total) "
                    + "VALUES (1, 'PENDING', 1.00) RETURNING EXTRACT(EPOCH FROM "
                    + "((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - created_at))")) {
                assertThat(row.next()).isTrue();
                assertThat(Math.abs(row.getDouble(1))).as("seconds between now (UTC) and the default").isLessThan(120);
            }
        }
    }

    // ================================================================== placing an order

    @Test
    void checkout_savesTheOrderAndItsLines_withCorrectTotals() throws Exception {
        shop().addToCart(userId, "SKU-A", 2, "100.00");
        shop().addToCart(userId, "SKU-B", 3, "20.50");

        HttpResponse<String> response = send("POST", "/api/orders", userToken);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode order = json(response);
        long orderId = order.get("id").asLong();
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("261.50");
        assertThat(order.get("userId").asLong()).isEqualTo(userId);
        assertThat(order.get("cartCleared").asBoolean()).isTrue();
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.get("items").get(0).get("sku").asText()).isEqualTo("SKU-A");
        assertThat(order.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(order.get("items").get(1).get("sku").asText()).isEqualTo("SKU-B");
        assertThat(order.get("items").get(1).get("lineTotal").decimalValue()).isEqualByComparingTo("61.50");

        // What PostgreSQL really holds.
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, orderId))
                .isEqualByComparingTo("261.50");
        assertThat(jdbc.queryForList("SELECT sku_code || ' x' || quantity || ' @' || unit_price FROM order_items "
                + "WHERE order_id = ? ORDER BY id", String.class, orderId))
                .containsExactly("SKU-A x2 @100.00", "SKU-B x3 @20.50");

        // The times are stored as UTC (hibernate.jdbc.time_zone=UTC), whatever the time zone of this machine is.
        assertThat(jdbc.queryForObject("SELECT ABS(EXTRACT(EPOCH FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') "
                + "- created_at))) FROM orders WHERE id = ?", Double.class, orderId)).isLessThan(120);

        // Reading it back gives the same order.
        JsonNode read = json(send("GET", "/api/orders/" + orderId, userToken));
        assertThat(read.get("total").decimalValue()).isEqualByComparingTo("261.50");
        assertThat(read.get("items")).hasSize(2);
        // The time in the JSON is the same after the round trip (stored as UTC, read back in the zone of this machine).
        LocalDateTime createdAtCheckout = LocalDateTime.parse(order.get("createdAt").asText());
        LocalDateTime createdAtRead = LocalDateTime.parse(read.get("createdAt").asText());
        assertThat(Duration.between(createdAtCheckout, createdAtRead).abs()).isLessThan(Duration.ofSeconds(1));
        assertThat(Duration.between(createdAtRead, LocalDateTime.now()).abs()).isLessThan(Duration.ofMinutes(2));
    }

    // ================================================================== lists

    @Test
    void myOrders_arePaged_newestFirst_andOnlyMine() throws Exception {
        long first = placeOrder(userId, "SKU-A", 1);
        long second = placeOrder(userId, "SKU-B", 1);
        long third = placeOrder(userId, "SKU-C", 1);
        placeOrder(userId + 5000, "SKU-C", 1);

        JsonNode pageOne = json(send("GET", "/api/orders?page=0&size=2", userToken));
        JsonNode pageTwo = json(send("GET", "/api/orders?page=1&size=2", userToken));

        assertThat(pageOne.get("totalElements").asLong()).isEqualTo(3);
        assertThat(pageOne.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(pageOne)).containsExactly(third, second);
        assertThat(ids(pageTwo)).containsExactly(first);
        assertThat(pageTwo.get("last").asBoolean()).isTrue();
        // The sort comes from the service (newest first), not from the request.
        assertThat(ids(json(send("GET", "/api/orders?sort=id,asc", userToken)))).containsExactly(third, second, first);
    }

    @Test
    void theSort_isByCreationTime_thenByHigherId_notByInsertOrder() throws Exception {
        // Inserted in this order, so the ids grow 1, 2, 3, 4 - but the creation times say otherwise.
        long newest = seed(userId, "CONFIRMED", "1.00", 50);
        long oldest = seed(userId, "CONFIRMED", "2.00", 10);
        long tieLowId = seed(userId, "CONFIRMED", "3.00", 30);
        long tieHighId = seed(userId, "CONFIRMED", "4.00", 30);

        assertThat(ids(json(send("GET", "/api/orders", userToken))))
                .containsExactly(newest, tieHighId, tieLowId, oldest);
        assertThat(ids(json(send("GET", "/api/orders/all", adminToken))))
                .containsExactly(newest, tieHighId, tieLowId, oldest);
    }

    @Test
    void adminList_showsEveryUser_andFiltersByStatus_withPagingAndCounts() throws Exception {
        long confirmedOne = seed(1, "CONFIRMED", "10.00", 1);
        long pending = seed(2, "PENDING", "20.00", 2);
        long rejected = seed(3, "REJECTED", "30.00", 3);
        long confirmedTwo = seed(4, "CONFIRMED", "40.00", 4);
        long confirmedThree = seed(5, "CONFIRMED", "50.00", 5);

        JsonNode everything = json(send("GET", "/api/orders/all", adminToken));
        assertThat(everything.get("totalElements").asLong()).isEqualTo(5);
        assertThat(ids(everything)).containsExactly(confirmedThree, confirmedTwo, rejected, pending, confirmedOne);

        JsonNode confirmed = json(send("GET", "/api/orders/all?status=CONFIRMED", adminToken));
        assertThat(ids(confirmed)).containsExactly(confirmedThree, confirmedTwo, confirmedOne);
        assertThat(confirmed.get("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(json(send("GET", "/api/orders/all?status=PENDING", adminToken)))).containsExactly(pending);
        JsonNode rejectedPage = json(send("GET", "/api/orders/all?status=REJECTED", adminToken));
        assertThat(ids(rejectedPage)).containsExactly(rejected);
        assertThat(rejectedPage.get("content").get(0).get("rejectReason").asText()).startsWith("Not enough stock");

        JsonNode secondPage = json(send("GET", "/api/orders/all?status=CONFIRMED&size=2&page=1", adminToken));
        assertThat(secondPage.get("totalElements").asLong()).isEqualTo(3);
        assertThat(secondPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(secondPage)).containsExactly(confirmedOne);

        // A page far past the end is empty, not an error (the largest page number is 100000).
        assertThat(json(send("GET", "/api/orders/all?page=100000&size=100", adminToken)).get("content")).isEmpty();
        // The lines of every order come with it.
        assertThat(everything.get("content").get(0).get("items")).hasSize(1);
    }

    // ================================================================== stats (GROUP BY, SUM, COUNT)

    @Test
    void stats_giveTheNumbersThatH2Gives_forTheSameData() throws Exception {
        // No orders: all zero, and the revenue still has two decimals (AdminOrdersTest, on H2, expects the same).
        String empty = send("GET", "/api/orders/stats", adminToken).body();
        assertThat(empty).contains("\"revenue\":0.00");
        assertThat(json(empty).get("totalOrders").asLong()).isZero();

        // The data of AdminOrdersTest.stats_countEveryStatus...: 5 orders, revenue 161.50 from the CONFIRMED ones only.
        seed(1, "CONFIRMED", "100.00", 1);
        seed(2, "CONFIRMED", "61.50", 2);
        seed(3, "PENDING", "5.00", 3);
        seed(4, "PENDING", "6.00", 4);
        seed(5, "REJECTED", "999.00", 5);

        String body = send("GET", "/api/orders/stats", adminToken).body();
        JsonNode stats = json(body);
        assertThat(stats.get("totalOrders").asLong()).isEqualTo(5);
        assertThat(stats.get("pending").asLong()).isEqualTo(2);
        assertThat(stats.get("confirmed").asLong()).isEqualTo(2);
        assertThat(stats.get("rejected").asLong()).isEqualTo(1);
        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo("161.50");
        assertThat(body).contains("\"revenue\":161.50");

        // Money adds up exactly (the data of AdminOrdersTest.stats_addUpMoneyExactly...).
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        seed(1, "CONFIRMED", "0.10", 1);
        seed(2, "CONFIRMED", "0.20", 2);
        seed(3, "CONFIRMED", "9999999999.99", 3);
        assertThat(json(send("GET", "/api/orders/stats", adminToken)).get("revenue").decimalValue())
                .isEqualByComparingTo("10000000000.29");
    }

    @Test
    void stats_followRealOrders_andAgreeWithAGroupByInTheDatabase() throws Exception {
        long other = userId + 6000;
        placeOrder(userId, "SKU-A", 2);                           // CONFIRMED 200.00
        placeOrder(other, "SKU-B", 1);                            // CONFIRMED  20.50
        shop().addToCart(userId, "SKU-B", 9, "20.50");
        assertThat(send("POST", "/api/orders", userToken).statusCode()).isEqualTo(409); // REJECTED 184.50
        seed(3, "PENDING", "5.00", 1);

        JsonNode stats = json(send("GET", "/api/orders/stats", adminToken));

        assertThat(stats.get("totalOrders").asLong()).isEqualTo(4);
        assertThat(stats.get("confirmed").asLong()).isEqualTo(2);
        assertThat(stats.get("rejected").asLong()).isEqualTo(1);
        assertThat(stats.get("pending").asLong()).isEqualTo(1);
        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo("220.50");
        // The same numbers from a plain GROUP BY.
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status",
                row -> { counts.put(row.getString(1), row.getLong(2)); });
        assertThat(counts).containsEntry("CONFIRMED", 2L).containsEntry("REJECTED", 1L).containsEntry("PENDING", 1L);
        assertThat(jdbc.queryForObject("SELECT SUM(total) FROM orders WHERE status = 'CONFIRMED'", BigDecimal.class))
                .isEqualByComparingTo(stats.get("revenue").decimalValue());
    }

    // ================================================================== the failure path

    @Test
    void aRejectedOrder_keepsItsStatus_andNoTransactionIsLeftAborted() throws Exception {
        // Stock refused for the second line: the order is REJECTED (409), its lines are kept.
        shop().addToCart(userId, "SKU-A", 2, "100.00");
        shop().addToCart(userId, "SKU-B", 9, "20.50");
        HttpResponse<String> refused = send("POST", "/api/orders", userToken);
        assertThat(refused.statusCode()).isEqualTo(409);
        long refusedId = json(refused).get("orderId").asLong();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, refusedId))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM orders WHERE id = ?", String.class, refusedId))
                .isEqualTo("Not enough stock for SKU-B: wanted 9, available 5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Long.class, refusedId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, refusedId))
                .isEqualByComparingTo("384.50");

        // The inventory service fails: REJECTED too (503).
        long otherUser = userId + 7000;
        shop().addToCart(otherUser, "SKU-C", 1, "5.00");
        shop().setReserveBehavior("SKU-C", Behavior.ERROR_500);
        HttpResponse<String> failed = send("POST", "/api/orders", JwtTestSupport.userToken(otherUser));
        assertThat(failed.statusCode()).isEqualTo(503);
        long failedId = json(failed).get("orderId").asLong();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, failedId))
                .isEqualTo("REJECTED");
        shop().setReserveBehavior("SKU-C", Behavior.NORMAL);

        // The rejected orders can be read, and they show as REJECTED.
        assertThat(json(send("GET", "/api/orders/" + refusedId, userToken)).get("status").asText()).isEqualTo("REJECTED");
        assertThat(json(send("GET", "/api/orders/" + failedId, JwtTestSupport.userToken(otherUser))).get("status")
                .asText()).isEqualTo("REJECTED");

        // No connection of the pool stays in a transaction (an aborted one would answer every later query with an error).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = ? "
                + "AND state LIKE 'idle in transaction%'", Long.class, APPLICATION_NAME)).isZero();

        // And the service goes on working: the next order (of another person) is saved and confirmed.
        long next = placeOrder(userId + 8000, "SKU-A", 1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, next))
                .isEqualTo("CONFIRMED");
        JsonNode stats = json(send("GET", "/api/orders/stats", adminToken));
        assertThat(stats.get("rejected").asLong()).isEqualTo(2);
        assertThat(stats.get("confirmed").asLong()).isEqualTo(1);
    }

    // ================================================================== helpers

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return json(response.body());
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(order -> ids.add(order.get("id").asLong()));
        return ids;
    }

    /** Puts one line in the cart of this user and checks out; returns the id of the new (confirmed) order. */
    private long placeOrder(long forUser, String sku, int quantity) throws Exception {
        shop().addToCart(forUser, sku, quantity, "1.00");
        HttpResponse<String> response = send("POST", "/api/orders", JwtTestSupport.userToken(forUser));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asLong();
    }

    /** Inserts an order with one line directly, with a chosen status, total and creation time. Returns its id. */
    private long seed(long forUser, String status, String total, int minutesAfterBase) {
        Timestamp created = Timestamp.valueOf(BASE_TIME.plusMinutes(minutesAfterBase));
        long id = jdbc.queryForObject("INSERT INTO orders (user_id, user_email, status, total, reject_reason, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                forUser, "u" + forUser + "@example.com", status, new BigDecimal(total),
                "REJECTED".equals(status) ? "Not enough stock for SKU-A: wanted 3, available 1" : null, created, created);
        jdbc.update("INSERT INTO order_items (order_id, sku_code, name, unit_price, quantity) "
                + "VALUES (?, 'SKU-A', 'Phone A', 10.00, 1)", id);
        return id;
    }

    private List<String> tablesIn(String schema) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, schema);
    }

    /** The versions of the migration files in the jar for PostgreSQL (common and postgresql), lowest first. */
    private static List<String> migrationVersionsInTheJar() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> versions = new ArrayList<>();
        for (String folder : List.of("common", "postgresql")) {
            for (Resource file : resolver.getResources("classpath*:db/migration/" + folder + "/V*__*.sql")) {
                String name = file.getFilename();
                versions.add(name.substring(1, name.indexOf("__")));
            }
        }
        versions.sort(Comparator.comparingInt(Integer::parseInt));
        return versions;
    }
}
