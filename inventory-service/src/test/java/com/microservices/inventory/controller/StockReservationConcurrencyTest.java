package com.microservices.inventory.controller;

import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review Focus 4: many callers at the same moment, over real HTTP, against a real server with the real
 * connection pool and database. Whatever the timing, the number of winners must be exactly what the stock
 * allows, stock must never be negative, and every number must add up.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
                "spring.datasource.url=jdbc:h2:mem:stock-reservation-concurrency-test;DB_CLOSE_DELAY=-1",
                "server-info.cloud-metadata.enabled=false",
                "inventory.product-service.url=http://localhost:1",
                // Keep the output readable: this test makes hundreds of requests.
                "spring.jpa.show-sql=false",
                "logging.level.org.hibernate.SQL=WARN",
                "logging.level.org.hibernate.type.descriptor.sql.BasicBinder=WARN",
                "logging.level.com.microservices.inventory=INFO",
                "logging.level.org.springframework.web=INFO"
        })
class StockReservationConcurrencyTest {

    private static final AtomicInteger SKU_COUNTER = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // ------------------------------------------------------------------ helpers

    private String newSku(int quantityOnHand) {
        String sku = "RACE-TEST-" + SKU_COUNTER.incrementAndGet();
        jdbc.update("INSERT INTO inventory_items (sku_code, quantity_on_hand) VALUES (?, ?)", sku, quantityOnHand);
        return sku;
    }

    private int post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", JwtTestSupport.serviceBearer())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Callable<Integer> reserveCall(String sku, int quantity, long orderId) {
        return () -> post("/api/inventory/sku/" + sku + "/reserve?quantity=" + quantity + "&orderId=" + orderId);
    }

    private Callable<Integer> releaseCall(String sku, long orderId) {
        return () -> post("/api/inventory/sku/" + sku + "/release?orderId=" + orderId);
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

    /** The books must balance: stock plus what is still reserved equals what we started with. */
    private void assertBooksBalance(String sku, int startingStock) {
        assertThat(stock(sku)).as("stock is never negative").isGreaterThanOrEqualTo(0);
        assertThat(stock(sku) + reservedQuantity(sku)).as("stock + reserved quantity").isEqualTo(startingStock);
    }

    // ------------------------------------------------------------------ the race for the last units

    @RepeatedTest(3)
    void manyReservesForTheLastUnits_withDifferentOrderIds_exactlyTheAvailableNumberWin() throws Exception {
        int available = 10;
        int callers = 60;
        String sku = newSku(available);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(reserveCall(sku, 1, 10_000 + i));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).as("status counts").containsOnlyKeys(200, 409);
        assertThat(statuses.get(200)).as("winners").isEqualTo(available);
        assertThat(statuses.get(409)).as("refused").isEqualTo(callers - available);
        assertThat(stock(sku)).isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ? AND status = 'RESERVED'", sku))
                .isEqualTo(available);
        assertThat(count("SELECT COUNT(*) FROM inventory_audit_log WHERE sku_code = ? AND change_type = 'RESERVED'", sku))
                .isEqualTo(available);
        assertBooksBalance(sku, available);
    }

    @RepeatedTest(3)
    void twoReservesForTheVeryLastUnit_exactlyOneWins() throws Exception {
        String sku = newSku(1);
        List<Callable<Integer>> calls = List.of(reserveCall(sku, 1, 20_001), reserveCall(sku, 1, 20_002));

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).isEqualTo(Map.of(200, 1, 409, 1));
        assertThat(stock(sku)).isZero();
        assertBooksBalance(sku, 1);
    }

    @Test
    void reservesOfSeveralUnits_neverTakeMoreThanIsThere() throws Exception {
        // 7 in stock, 20 callers want 2 each: only 3 fit (6 units), 1 unit stays.
        String sku = newSku(7);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(reserveCall(sku, 2, 30_000 + i));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).containsOnlyKeys(200, 409);
        assertThat(statuses.get(200)).isEqualTo(3);
        assertThat(stock(sku)).isEqualTo(1);
        assertBooksBalance(sku, 7);
    }

    @Test
    void reservesOfMixedSizes_keepTheBooksBalanced_andNeverGoNegative() throws Exception {
        String sku = newSku(50);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            calls.add(reserveCall(sku, 1 + (i % 5), 40_000 + i));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).containsOnlyKeys(200, 409);
        assertBooksBalance(sku, 50);
        assertThat(stock(sku)).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ the same order twice at the same moment

    @RepeatedTest(3)
    void theSameReserveSentManyTimesAtOnce_changesStockOnce_andEveryoneGets200() throws Exception {
        String sku = newSku(100);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            calls.add(reserveCall(sku, 3, 50_000));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).isEqualTo(Map.of(200, 25));
        assertThat(stock(sku)).isEqualTo(97);
        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM inventory_audit_log WHERE sku_code = ? AND change_type = 'RESERVED'", sku))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ release at the same moment

    @RepeatedTest(3)
    void theSameReleaseSentManyTimesAtOnce_putsTheStockBackOnce() throws Exception {
        String sku = newSku(10);
        assertThat(post("/api/inventory/sku/" + sku + "/reserve?quantity=4&orderId=60000")).isEqualTo(200);
        assertThat(stock(sku)).isEqualTo(6);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            calls.add(releaseCall(sku, 60_000));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        assertThat(statuses).isEqualTo(Map.of(200, 25));
        assertThat(stock(sku)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM inventory_audit_log WHERE sku_code = ? AND change_type = 'RELEASED'", sku))
                .isEqualTo(1);
        assertBooksBalance(sku, 10);
    }

    @Test
    void reservesAndReleasesAtTheSameMoment_keepTheBooksBalanced() throws Exception {
        String sku = newSku(30);
        // 20 orders hold 1 unit each (10 left) ...
        for (int i = 0; i < 20; i++) {
            assertThat(post("/api/inventory/sku/" + sku + "/reserve?quantity=1&orderId=" + (70_000 + i))).isEqualTo(200);
        }
        // ... then those 20 are released while 30 new orders (each wants 1) try to reserve
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(releaseCall(sku, 70_000 + i));
        }
        for (int i = 0; i < 30; i++) {
            calls.add(reserveCall(sku, 1, 71_000 + i));
        }

        Map<Integer, Integer> statuses = fireTogether(calls);

        // Depending on the timing a new reserve finds stock or not, so 200 and 409 are both fine. Nothing else is.
        assertThat(statuses.keySet()).isSubsetOf(200, 409);
        assertThat(statuses.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(50);
        assertBooksBalance(sku, 30);
        // Every release answered 200 and every release really happened: no old order still holds stock
        assertThat(count("SELECT COUNT(*) FROM stock_reservations WHERE sku_code = ? AND order_id BETWEEN 70000 AND 70019 AND status = 'RESERVED'", sku))
                .isZero();
    }
}
