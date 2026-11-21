package com.microservices.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.inventory.model.InventoryItem;
import com.microservices.inventory.repository.InventoryRepository;
import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test: the whole service on a real PostgreSQL, with the "postgres" profile (the way it runs on
 * Amazon RDS). The normal build skips it. To run it, point it at a PostgreSQL server you can write to:
 *
 *   TEST_PG_URL=jdbc:postgresql://localhost:5432/microshop TEST_PG_USER=... TEST_PG_PASSWORD=... \
 *       mvn test -Dtest=PostgresSmokeTest
 *
 * It uses its own schema, inventory_svc_test, and drops it before every run (before the application starts),
 * so it can be run again and again. Nothing else in the database is touched.
 *
 * What it proves: Flyway ran the PostgreSQL migrations, Hibernate "validate" accepted the tables (the
 * application started), and the stock rules hold on PostgreSQL: the race for the last units, repeat-safe
 * reserve and release, the audit log, and the SERVICE-role rules.
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
                "server-info.cloud-metadata.enabled=false",
                "inventory.product-service.url=http://localhost:1",
                // Keep the output readable: this test makes hundreds of requests.
                "spring.jpa.show-sql=false",
                "logging.level.org.hibernate.SQL=WARN",
                "logging.level.org.hibernate.type.descriptor.sql.BasicBinder=WARN",
                "logging.level.com.microservices.inventory=INFO",
                "logging.level.org.springframework.web=INFO"
        })
@ActiveProfiles({"test", "postgres"})
class PostgresSmokeTest {

    private static final String SCHEMA = "inventory_svc_test";
    private static final AtomicInteger SKU_COUNTER = new AtomicInteger();

    /**
     * Runs while the application context is being built, so before Flyway and before Hibernate:
     * drop the test schema with plain JDBC, then hand the connection settings to the "postgres" profile.
     */
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) throws SQLException {
        String url = System.getenv("TEST_PG_URL");
        String user = System.getenv("TEST_PG_USER");
        String password = System.getenv("TEST_PG_PASSWORD");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }

        registry.add("DB_URL", () -> url);
        registry.add("DB_USERNAME", () -> user);
        registry.add("DB_PASSWORD", () -> password);
        registry.add("DB_SCHEMA", () -> SCHEMA);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // ------------------------------------------------------------------ helpers

    private record Reply(int status, String body) {
    }

    private String newSku(int quantityOnHand) {
        String sku = "PG-SMOKE-" + SKU_COUNTER.incrementAndGet();
        jdbc.update("INSERT INTO inventory_items (sku_code, quantity_on_hand) VALUES (?, ?)", sku, quantityOnHand);
        return sku;
    }

    private Reply send(String method, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private int post(String path) throws Exception {
        return send("POST", path, JwtTestSupport.serviceBearer()).status();
    }

    private String reservePath(String sku, int quantity, long orderId) {
        return "/api/inventory/sku/" + sku + "/reserve?quantity=" + quantity + "&orderId=" + orderId;
    }

    private String releasePath(String sku, long orderId) {
        return "/api/inventory/sku/" + sku + "/release?orderId=" + orderId;
    }

    private Callable<Integer> reserveCall(String sku, int quantity, long orderId) {
        return () -> post(reservePath(sku, quantity, orderId));
    }

    private Callable<Integer> releaseCall(String sku, long orderId) {
        return () -> post(releasePath(sku, orderId));
    }

    /** Runs all calls at the same moment (every thread waits at a gate) and returns how often each status came back. */
    private Map<Integer, Integer> fireTogether(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            go.countDown();

            Map<Integer, Integer> statuses = new TreeMap<>();
            for (Future<Integer> future : futures) {
                statuses.merge(future.get(120, TimeUnit.SECONDS), 1, Integer::sum);
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private int stock(String sku) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory_items WHERE sku_code = ?", Integer.class, sku);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int reservedQuantity(String sku) {
        return count("SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations WHERE sku_code = ? AND status = 'RESERVED'", sku);
    }

    /** The audit rows of a SKU in the order they were written. */
    private List<Map<String, Object>> auditRows(String sku) {
        return jdbc.queryForList("SELECT change_type, previous_quantity, new_quantity FROM inventory_audit_log "
                + "WHERE sku_code = ? ORDER BY id", sku);
    }

    /** The books must balance: stock plus what is still reserved equals what we started with. */
    private void assertBooksBalance(String sku, int startingStock) {
        assertThat(stock(sku)).as("stock is never negative").isGreaterThanOrEqualTo(0);
        assertThat(stock(sku) + reservedQuantity(sku)).as("stock + reserved quantity").isEqualTo(startingStock);
    }

    // ------------------------------------------------------------------ the database set-up

    @Test
    void flywayRanAllMigrationsInTheTestSchema_andHibernateValidatedThem() {
        // The application started with ddl-auto=validate, so Hibernate accepted every mapped table on PostgreSQL
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, description, success FROM " + SCHEMA + ".flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).hasSize(3);
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3");
        assertThat(history).extracting(row -> row.get("description")).containsExactly(
                "create inventory table", "seed inventory", "create stock reservations");

        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        Set<String> tables = new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, SCHEMA));
        assertThat(tables).containsExactlyInAnyOrder(
                "flyway_schema_history", "inventory_items", "inventory_audit_log", "stock_reservations");

        // The seed data is the same as on H2
        assertThat(count("SELECT COUNT(*) FROM inventory_items WHERE sku_code NOT LIKE 'PG-SMOKE-%'")).isEqualTo(5010);
        assertThat(count("SELECT COUNT(*) FROM inventory_audit_log WHERE change_type = 'CREATED'")).isEqualTo(5010);
    }

    @Test
    void hibernateReadsTheSeededRows_andTheIdentityColumnsHandOutNewIds() {
        InventoryItem item = inventoryRepository.findBySkuCode("APPLE-IP15P-128").orElseThrow();
        assertThat(item.getQuantityOnHand()).isEqualTo(13);
        assertThat(item.getReorderThreshold()).isEqualTo(10);

        long idBefore = inventoryRepository.findBySkuCode("ELEC-00001").orElseThrow().getId();
        String sku = newSku(3);
        long idNew = inventoryRepository.findBySkuCode(sku).orElseThrow().getId();
        assertThat(idNew).as("a new row gets an id above every seeded id").isGreaterThan(5010).isGreaterThan(idBefore);
    }

    // ------------------------------------------------------------------ the race for the last units

    @Test
    void twentyCallersForFiveUnits_exactlyFiveWin_theOtherFifteenAreRefused_stockEndsAtZero() throws Exception {
        String sku = newSku(5);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(reserveCall(sku, 1, 1_000 + i)); // 20 different order ids
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).as("status counts").isEqualTo(Map.of(200, 5, 409, 15));
        assertThat(stock(sku)).as("stock ends at exactly 0").isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ? AND status = 'RESERVED'", sku)).isEqualTo(5);
        assertThat(count("SELECT COUNT(DISTINCT order_id) FROM stock_reservations WHERE sku_code = ?", sku)).isEqualTo(5);
        assertBooksBalance(sku, 5);

        // The audit log tells the same story: five RESERVED rows, each one unit, counting 5->4, 4->3 ... 1->0
        List<Map<String, Object>> audit = auditRows(sku).stream()
                .filter(row -> "RESERVED".equals(row.get("change_type"))).toList();
        assertThat(audit).hasSize(5);
        assertThat(audit).allSatisfy(row ->
                assertThat((Integer) row.get("previous_quantity") - (Integer) row.get("new_quantity")).isEqualTo(1));
        assertThat(audit.stream().map(row -> (Integer) row.get("new_quantity")).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(4, 3, 2, 1, 0);
        assertThat(auditRows(sku)).as("no other audit rows").hasSize(5);
    }

    @Test
    void reserveAgainForAnOrderThatAlreadyReserved_changesNothing() throws Exception {
        String sku = newSku(10);
        assertThat(post(reservePath(sku, 3, 2_001))).isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(7);

        assertThat(post(reservePath(sku, 3, 2_001))).as("the same call again").isEqualTo(200);
        assertThat(post(reservePath(sku, 3, 2_001))).as("and again").isEqualTo(200);

        assertThat(stock(sku)).as("no second deduction").isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ?", sku)).isEqualTo(1);
        assertThat(auditRows(sku)).hasSize(1);
        // Another quantity for the same order is a conflict, not a second deduction
        assertThat(post(reservePath(sku, 5, 2_001))).isEqualTo(409);
        assertThat(stock(sku)).isEqualTo(7);
    }

    @Test
    void theSameReserveSentManyTimesAtOnce_changesStockOnce_andEveryoneGets200() throws Exception {
        String sku = newSku(100);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(reserveCall(sku, 3, 3_001));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).isEqualTo(Map.of(200, 20));
        assertThat(stock(sku)).isEqualTo(97);
        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ?", sku)).isEqualTo(1);
        assertThat(auditRows(sku)).hasSize(1);
    }

    // ------------------------------------------------------------------ release

    @Test
    void release_givesStockBackOnceAndOnlyOnce() throws Exception {
        String sku = newSku(10);
        assertThat(post(reservePath(sku, 4, 4_001))).isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(6);

        assertThat(post(releasePath(sku, 4_001))).isEqualTo(200);
        assertThat(stock(sku)).as("the units are back").isEqualTo(10);

        assertThat(post(releasePath(sku, 4_001))).as("the second release").isEqualTo(200);
        assertThat(stock(sku)).as("not added a second time").isEqualTo(10);
        assertThat(post(releasePath(sku, 4_999))).as("release of an order that never reserved").isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(10);

        // A released line cannot be reserved again under the same order id
        assertThat(post(reservePath(sku, 4, 4_001))).isEqualTo(409);
        assertThat(stock(sku)).isEqualTo(10);

        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ? AND status = 'RELEASED'", sku)).isEqualTo(1);
        // The audit log matches the stock changes: 10 -> 6 (reserved), 6 -> 10 (released), nothing else
        List<Map<String, Object>> audit = auditRows(sku);
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).containsEntry("change_type", "RESERVED").containsEntry("previous_quantity", 10).containsEntry("new_quantity", 6);
        assertThat(audit.get(1)).containsEntry("change_type", "RELEASED").containsEntry("previous_quantity", 6).containsEntry("new_quantity", 10);
        assertBooksBalance(sku, 10);
    }

    @Test
    void theSameReleaseSentManyTimesAtOnce_putsTheStockBackOnce() throws Exception {
        String sku = newSku(10);
        assertThat(post(reservePath(sku, 4, 5_001))).isEqualTo(200);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(releaseCall(sku, 5_001));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).isEqualTo(Map.of(200, 20));
        assertThat(stock(sku)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM inventory_audit_log WHERE sku_code = ? AND change_type = 'RELEASED'", sku)).isEqualTo(1);
        assertBooksBalance(sku, 10);
    }

    // ------------------------------------------------------------------ time stamps

    @Test
    void timestamps_areUTC_whetherHibernateWroteThemOrTheDatabaseDid() throws Exception {
        // created_at / changed_at come from Hibernate (hibernate.jdbc.time_zone=UTC); the default values and the
        // native "updated_at = CURRENT_TIMESTAMP" statements come from the database session. All must say UTC,
        // also when the JVM runs in another time zone (this test's JVM may do), or one row would mix two clocks.
        String sku = newSku(5);
        assertThat(post(reservePath(sku, 1, 8_001))).isEqualTo(200);

        Double itemDefaultOrNative = jdbc.queryForObject("SELECT ABS(EXTRACT(EPOCH FROM (updated_at - (now() AT TIME ZONE 'UTC')))) "
                + "FROM inventory_items WHERE sku_code = ?", Double.class, sku);
        Double auditByHibernate = jdbc.queryForObject("SELECT ABS(EXTRACT(EPOCH FROM (changed_at - (now() AT TIME ZONE 'UTC')))) "
                + "FROM inventory_audit_log WHERE sku_code = ?", Double.class, sku);
        Double reservationCreatedByHibernate = jdbc.queryForObject("SELECT ABS(EXTRACT(EPOCH FROM (created_at - (now() AT TIME ZONE 'UTC')))) "
                + "FROM stock_reservations WHERE sku_code = ?", Double.class, sku);
        Double reservationUpdatedByDatabase = jdbc.queryForObject("SELECT ABS(EXTRACT(EPOCH FROM (updated_at - (now() AT TIME ZONE 'UTC')))) "
                + "FROM stock_reservations WHERE sku_code = ?", Double.class, sku);

        assertThat(itemDefaultOrNative).as("inventory_items.updated_at (native UPDATE): seconds from UTC now").isLessThan(120);
        assertThat(auditByHibernate).as("inventory_audit_log.changed_at (Hibernate): seconds from UTC now").isLessThan(120);
        assertThat(reservationCreatedByHibernate).as("stock_reservations.created_at (Hibernate): seconds from UTC now").isLessThan(120);
        assertThat(reservationUpdatedByDatabase).as("stock_reservations.updated_at (database default): seconds from UTC now").isLessThan(120);
    }

    // ------------------------------------------------------------------ audit endpoint, low-stock, admin writes

    @Test
    void auditEndpoint_listsTheChangesNewestFirst() throws Exception {
        String sku = newSku(10);
        assertThat(post(reservePath(sku, 2, 6_001))).isEqualTo(200);
        Thread.sleep(20); // changed_at is the order; keep the two rows apart
        assertThat(post(releasePath(sku, 6_001))).isEqualTo(200);

        Reply reply = send("GET", "/api/inventory/sku/" + sku + "/audit", null);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode content = objectMapper.readTree(reply.body()).get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("changeType").asText()).isEqualTo("RELEASED");
        assertThat(content.get(1).get("changeType").asText()).isEqualTo("RESERVED");
    }

    @Test
    void lowStockEndpoint_returnsTheOutOfStockItemsFirst() throws Exception {
        Reply reply = send("GET", "/api/inventory/low-stock?size=100", null);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode content = objectMapper.readTree(reply.body()).get("content");
        assertThat(content).hasSize(100);
        int previous = -1;
        for (JsonNode item : content) {
            int quantity = item.get("quantityOnHand").asInt();
            assertThat(quantity).isGreaterThanOrEqualTo(previous);
            assertThat(quantity).isLessThanOrEqualTo(item.get("reorderThreshold").asInt());
            previous = quantity;
        }
    }

    // ------------------------------------------------------------------ the SERVICE-role rules

    @Test
    void reserveAndRelease_onlyAcceptTheSERVICERole() throws Exception {
        String sku = newSku(10);
        String reserve = reservePath(sku, 1, 7_001);
        String release = releasePath(sku, 7_001);

        for (String path : List.of(reserve, release)) {
            assertThat(send("POST", path, null).status()).as("no token: " + path).isEqualTo(401);
            assertThat(send("POST", path, "Bearer not-a-token").status()).as("bad token: " + path).isEqualTo(401);
            assertThat(send("POST", path, JwtTestSupport.bearer("USER")).status()).as("USER: " + path).isEqualTo(403);
            assertThat(send("POST", path, JwtTestSupport.bearer("ADMIN")).status()).as("ADMIN: " + path).isEqualTo(403);
        }
        assertThat(stock(sku)).as("the refused calls changed nothing").isEqualTo(10);
        assertThat(auditRows(sku)).isEmpty();

        assertThat(send("POST", reserve, JwtTestSupport.serviceBearer()).status()).as("SERVICE reserve").isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(9);
        assertThat(send("POST", release, JwtTestSupport.serviceBearer()).status()).as("SERVICE release").isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(10);
    }

    @Test
    void aSERVICEToken_isRefusedOnOtherWrites_andAnADMINTokenCanAdjustStock() throws Exception {
        String sku = newSku(10);
        String adjust = "/api/inventory/sku/" + sku + "/adjust?delta=5";

        assertThat(send("PATCH", adjust, JwtTestSupport.serviceBearer()).status()).isEqualTo(403);
        assertThat(send("PATCH", adjust, JwtTestSupport.bearer("USER")).status()).isEqualTo(403);
        assertThat(send("PATCH", adjust, null).status()).isEqualTo(401);
        assertThat(stock(sku)).isEqualTo(10);

        assertThat(send("PATCH", adjust, JwtTestSupport.bearer("ADMIN")).status()).isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(15);
        List<Map<String, Object>> audit = auditRows(sku);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).containsEntry("change_type", "ADJUSTED").containsEntry("previous_quantity", 10).containsEntry("new_quantity", 15);
    }
}
