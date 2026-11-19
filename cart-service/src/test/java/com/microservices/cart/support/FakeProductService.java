package com.microservices.cart.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A small fake of product-service for the tests: it answers GET /api/products/sku/{sku} over real HTTP
 * on a random port (never 8081). A test can add or remove products, make every answer fail, delay the
 * answers, and look at the requests that arrived.
 */
public final class FakeProductService {

    private static final String PREFIX = "/api/products/sku/";

    private static FakeProductService shared;

    /** One fake for the whole test run, so every test class can use the same URL (and the same Spring context). */
    public static synchronized FakeProductService shared() {
        if (shared == null) {
            shared = new FakeProductService();
        }
        return shared;
    }

    /**
     * What the cart service sent: method, the raw (still encoded) path, the Authorization header and the
     * trace headers (traceparent of the W3C standard, and the B3 header, which is only there if B3 is switched on).
     */
    public record Received(String method, String rawPath, String authorization, String traceparent, String b3) {
    }

    private record Product(String name, String price) {
    }

    private final HttpServer server;
    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Map<String, String> rawBodies = new ConcurrentHashMap<>();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private volatile int failWithStatus = 0;
    private volatile long delayMillis = 0;

    public FakeProductService() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("The fake product service could not start", e);
        }
        server.createContext("/", this::handle);
        // many threads, so a delayed answer does not block the other requests
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-product-service");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    /** Back to the start state: no products, no failure, no delay, no recorded requests. */
    public void reset() {
        products.clear();
        rawBodies.clear();
        requests.clear();
        failWithStatus = 0;
        delayMillis = 0;
    }

    public void addProduct(String sku, String name, String price) {
        products.put(sku, new Product(name, price));
    }

    public void removeProduct(String sku) {
        products.remove(sku);
        rawBodies.remove(sku);
    }

    /** Every lookup answers this status with an empty body (for example 500). */
    public void failWithStatus(int status) {
        this.failWithStatus = status;
    }

    /** Answer exactly this body (status 200) for a SKU, to test answers that are not what we expect. */
    public void answerWithRawBody(String sku, String body) {
        rawBodies.put(sku, body);
    }

    public void delayMillis(long millis) {
        this.delayMillis = millis;
    }

    public List<Received> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String rawPath = exchange.getRequestURI().getRawPath();
            requests.add(new Received(exchange.getRequestMethod(), rawPath,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("traceparent"),
                    exchange.getRequestHeaders().getFirst("b3")));

            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failWithStatus != 0) {
                reply(exchange, failWithStatus, "");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod()) || !rawPath.startsWith(PREFIX)) {
                reply(exchange, 404, "{}");
                return;
            }
            String sku = java.net.URLDecoder.decode(rawPath.substring(PREFIX.length()), StandardCharsets.UTF_8);
            if (rawBodies.containsKey(sku)) {
                reply(exchange, 200, rawBodies.get(sku));
                return;
            }
            Product product = products.get(sku);
            if (product == null) {
                reply(exchange, 404, "{\"title\":\"Product Not Found\",\"status\":404}");
                return;
            }
            // Same shape as the real product-service, with fields the cart does not use.
            reply(exchange, 200, "{\"id\":1,\"name\":\"" + product.name() + "\",\"description\":\"text\","
                    + "\"price\":" + product.price() + ",\"category\":\"Electronics\",\"skuCode\":\"" + sku + "\","
                    + "\"stockQuantity\":5}");
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
