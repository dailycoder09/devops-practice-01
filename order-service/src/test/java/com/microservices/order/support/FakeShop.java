package com.microservices.order.support;

import com.microservices.order.support.FakeHttpServer.Reply;
import com.microservices.order.support.FakeHttpServer.Request;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The three services that order-service calls (cart, product, inventory), as fake HTTP servers
 * that follow the contract of the build plan and keep their own state:
 *
 * <ul>
 *   <li>cart: one cart per user (the user is read from the Bearer token), GET and DELETE of /api/cart</li>
 *   <li>product: a catalogue, GET /api/products/sku/{sku}</li>
 *   <li>inventory: stock per SKU and stored reservations per (orderId, SKU), with the repeat-safe
 *       reserve and release of the contract. It accepts only a correctly signed token with role SERVICE.</li>
 * </ul>
 *
 * Every fake can be told to fail: answer 500, cut the connection, answer too late, or be down.
 * One instance lives for the whole test run (ports stay the same, so Spring can reuse its context).
 */
public final class FakeShop {

    private static FakeShop instance;

    /** The shared instance, started on first use. */
    public static synchronized FakeShop get() {
        if (instance == null) {
            instance = new FakeShop();
        }
        return instance;
    }

    /** How long a "late" answer is held back. It must be longer than the read time-out of the tests (600 ms). */
    public static final long LATE_MILLIS = 2500;

    /** What a fake does with a call. */
    public enum Behavior {
        /** Works as the contract says. */
        NORMAL,
        /** Answers 500 and changes nothing. */
        ERROR_500,
        /** Cuts the connection and changes nothing. */
        DROP,
        /** Does the work, but answers too late (the caller gives up and never sees the answer). */
        APPLY_THEN_LATE,
        /** Does the work, then cuts the connection (the caller never sees the answer). */
        APPLY_THEN_DROP,
        /** Answers 401 (as if it did not accept the token). */
        UNAUTHORIZED
    }

    private record CartLine(String sku, int quantity, BigDecimal shownPrice) {
    }

    private record Product(String name, BigDecimal price) {
    }

    private record Reservation(int quantity, boolean released) {
    }

    public final FakeHttpServer cartServer = FakeHttpServer.start();
    public final FakeHttpServer productServer = FakeHttpServer.start();
    public final FakeHttpServer inventoryServer = FakeHttpServer.start();

    // cart state
    private final Map<Long, Map<String, CartLine>> carts = new LinkedHashMap<>();
    private Behavior cartReadBehavior = Behavior.NORMAL;
    private Behavior cartClearBehavior = Behavior.NORMAL;
    private String cartReadBody;

    // product state
    private final Map<String, Product> products = new LinkedHashMap<>();
    private Behavior productBehavior = Behavior.NORMAL;

    // inventory state
    private final Map<String, Integer> stock = new LinkedHashMap<>();
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private final Map<String, Behavior> reserveBehavior = new LinkedHashMap<>();
    private final Map<String, Behavior> releaseBehavior = new LinkedHashMap<>();
    private BiConsumer<String, Long> onReserve = (sku, orderId) -> { };

    private FakeShop() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cartServer.close();
            productServer.close();
            inventoryServer.close();
        }));
        reset();
    }

    // ------------------------------------------------------------------------------------ setup helpers

    /** Back to a clean start: empty carts, no products, no stock, no failures. All servers are running. */
    public synchronized void reset() {
        for (FakeHttpServer server : List.of(cartServer, productServer, inventoryServer)) {
            server.restartIfStopped();
            server.reset();
        }
        carts.clear();
        products.clear();
        stock.clear();
        reservations.clear();
        reserveBehavior.clear();
        releaseBehavior.clear();
        cartReadBehavior = Behavior.NORMAL;
        cartClearBehavior = Behavior.NORMAL;
        cartReadBody = null;
        productBehavior = Behavior.NORMAL;
        onReserve = (sku, orderId) -> { };

        cartServer.on("GET", "/api/cart", this::readCart);
        cartServer.on("DELETE", "/api/cart", this::clearCart);
        productServer.onPrefix("GET", "/api/products/sku/",
                request -> answerProduct(request.path().substring("/api/products/sku/".length())));
        inventoryServer.onPrefix("POST", "/api/inventory/sku/", this::inventoryCall);
    }

    public synchronized void addProduct(String sku, String name, String price) {
        products.put(sku, new Product(name, new BigDecimal(price)));
    }

    /** Removes the product: product-service now answers 404 for it. */
    public synchronized void removeProduct(String sku) {
        products.remove(sku);
    }

    public synchronized void setPrice(String sku, String price) {
        products.put(sku, new Product(products.get(sku).name(), new BigDecimal(price)));
    }

    public synchronized void addStock(String sku, int quantity) {
        stock.put(sku, quantity);
    }

    /** Puts a line in the cart of this user. {@code shownPrice} is the (maybe old) price the cart shows. */
    public synchronized void addToCart(long userId, String sku, int quantity, String shownPrice) {
        carts.computeIfAbsent(userId, id -> new LinkedHashMap<>())
                .put(sku, new CartLine(sku, quantity, new BigDecimal(shownPrice)));
    }

    public synchronized int cartSize(long userId) {
        return carts.getOrDefault(userId, Map.of()).size();
    }

    public synchronized int stockOf(String sku) {
        return stock.get(sku);
    }

    /** Number of stored reservations that are still RESERVED. */
    public synchronized long activeReservations() {
        return reservations.values().stream().filter(r -> !r.released()).count();
    }

    public synchronized void setCartReadBehavior(Behavior behavior) {
        cartReadBehavior = behavior;
    }

    public synchronized void setCartClearBehavior(Behavior behavior) {
        cartClearBehavior = behavior;
    }

    /** The cart answers this exact body (for a cart that sends something odd). */
    public synchronized void setCartReadBody(String body) {
        cartReadBody = body;
    }

    public synchronized void setProductBehavior(Behavior behavior) {
        productBehavior = behavior;
    }

    public synchronized void setReserveBehavior(String sku, Behavior behavior) {
        reserveBehavior.put(sku, behavior);
    }

    public synchronized void setReleaseBehavior(String sku, Behavior behavior) {
        releaseBehavior.put(sku, behavior);
    }

    /** Called (on the server thread) each time a reserve call arrives, before it is handled. */
    public synchronized void onReserve(BiConsumer<String, Long> callback) {
        onReserve = callback;
    }

    // ------------------------------------------------------------------------------------ cart

    private Reply readCart(Request request) {
        Behavior behavior;
        String fixedBody;
        synchronized (this) {
            behavior = cartReadBehavior;
            fixedBody = cartReadBody;
        }
        Reply failure = failure(behavior);
        if (failure != null) {
            return failure;
        }
        Long userId = userIdOf(request);
        if (userId == null) {
            return Reply.json(401, problem(401, "Unauthorized", "No valid token"));
        }
        if (fixedBody != null) {
            return afterApply(behavior, Reply.ok(fixedBody));
        }
        synchronized (this) {
            List<String> items = new ArrayList<>();
            int itemCount = 0;
            BigDecimal total = BigDecimal.ZERO;
            for (CartLine line : carts.getOrDefault(userId, Map.of()).values()) {
                BigDecimal lineTotal = line.shownPrice().multiply(BigDecimal.valueOf(line.quantity()));
                items.add("{\"sku\":\"" + line.sku() + "\",\"quantity\":" + line.quantity()
                        + ",\"available\":true,\"name\":\"" + line.sku() + "\",\"unitPrice\":" + line.shownPrice()
                        + ",\"lineTotal\":" + lineTotal + "}");
                itemCount += line.quantity();
                total = total.add(lineTotal);
            }
            return afterApply(behavior, Reply.ok("{\"items\":[" + String.join(",", items) + "],\"itemCount\":"
                    + itemCount + ",\"total\":" + total + "}"));
        }
    }

    private Reply clearCart(Request request) {
        Behavior behavior;
        synchronized (this) {
            behavior = cartClearBehavior;
        }
        Reply failure = failure(behavior);
        if (failure != null) {
            return failure;
        }
        Long userId = userIdOf(request);
        if (userId == null) {
            return Reply.json(401, problem(401, "Unauthorized", "No valid token"));
        }
        synchronized (this) {
            carts.remove(userId);
        }
        return afterApply(behavior, Reply.noContent());
    }

    // ------------------------------------------------------------------------------------ product

    private Reply answerProduct(String sku) {
        Behavior behavior;
        Product product;
        synchronized (this) {
            behavior = productBehavior;
            product = products.get(sku);
        }
        Reply failure = failure(behavior);
        if (failure != null) {
            return failure;
        }
        if (product == null) {
            return Reply.json(404, problem(404, "Product Not Found", "Product not found with SKU: " + sku));
        }
        return afterApply(behavior, Reply.ok("{\"id\":1,\"name\":\"" + product.name()
                + "\",\"description\":\"d\",\"price\":" + product.price() + ",\"category\":\"c\",\"skuCode\":\""
                + sku + "\",\"stockQuantity\":null}"));
    }

    // ------------------------------------------------------------------------------------ inventory

    /** POST /api/inventory/sku/{sku}/reserve and /release. Any other path is a 404 like the real service. */
    private Reply inventoryCall(Request request) {
        String rest = request.path().substring("/api/inventory/sku/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Reply.json(404, problem(404, "Not Found", "No such path"));
        }
        String sku = rest.substring(0, slash);
        String action = rest.substring(slash + 1);
        Map<String, String> query = query(request);

        Reply denied = checkServiceToken(request);
        if (denied != null) {
            return denied;
        }
        synchronized (this) {
            if (!stock.containsKey(sku)) {
                return Reply.json(404, problem(404, "Inventory Record Not Found", "No inventory for SKU: " + sku));
            }
        }
        return switch (action) {
            case "reserve" -> reserve(sku, query);
            case "release" -> release(sku, query);
            default -> Reply.json(404, problem(404, "Not Found", "No such path"));
        };
    }

    private Reply reserve(String sku, Map<String, String> query) {
        long orderId = Long.parseLong(query.get("orderId"));
        BiConsumer<String, Long> callback;
        Behavior behavior;
        synchronized (this) {
            callback = onReserve;
            behavior = reserveBehavior.getOrDefault(sku, Behavior.NORMAL);
        }
        callback.accept(sku, orderId);
        Reply failure = failure(behavior);
        if (failure != null) {
            return failure;
        }
        return afterApply(behavior, applyReserve(sku, Integer.parseInt(query.get("quantity")), orderId));
    }

    private synchronized Reply applyReserve(String sku, int quantity, long orderId) {
        String key = orderId + "|" + sku;
        Reservation existing = reservations.get(key);
        if (existing != null) {
            boolean same = !existing.released() && existing.quantity() == quantity;
            return same ? Reply.ok(inventoryJson(sku))
                    : Reply.json(409, problem(409, "Conflict", "This order and SKU were already used"));
        }
        int available = stock.get(sku);
        if (available < quantity) {
            return Reply.json(409, problem(409, "Conflict",
                    "Not enough stock for " + sku + ": wanted " + quantity + ", available " + available));
        }
        stock.put(sku, available - quantity);
        reservations.put(key, new Reservation(quantity, false));
        return Reply.ok(inventoryJson(sku));
    }

    private Reply release(String sku, Map<String, String> query) {
        long orderId = Long.parseLong(query.get("orderId"));
        Behavior behavior;
        synchronized (this) {
            behavior = releaseBehavior.getOrDefault(sku, Behavior.NORMAL);
        }
        Reply failure = failure(behavior);
        if (failure != null) {
            return failure;
        }
        return afterApply(behavior, applyRelease(sku, orderId));
    }

    private synchronized Reply applyRelease(String sku, long orderId) {
        String key = orderId + "|" + sku;
        Reservation existing = reservations.get(key);
        if (existing != null && !existing.released()) {
            stock.put(sku, stock.get(sku) + existing.quantity());
            reservations.put(key, new Reservation(existing.quantity(), true));
        }
        return Reply.ok(inventoryJson(sku));
    }

    private static Reply afterApply(Behavior behavior, Reply result) {
        return switch (behavior) {
            case APPLY_THEN_LATE -> result.after(LATE_MILLIS);
            case APPLY_THEN_DROP -> Reply.drop();
            default -> result;
        };
    }

    private synchronized String inventoryJson(String sku) {
        return "{\"id\":1,\"skuCode\":\"" + sku + "\",\"quantityOnHand\":" + stock.get(sku)
                + ",\"reorderThreshold\":5}";
    }

    /** Only a correctly signed, unexpired token with role SERVICE gets through (like the real inventory-service). */
    private static Reply checkServiceToken(Request request) {
        JWTClaimsSet claims = verifiedClaims(request);
        if (claims == null) {
            return Reply.json(401, problem(401, "Unauthorized", "A valid login token is needed."));
        }
        if (!"SERVICE".equals(claims.getClaim("role"))) {
            return Reply.json(403, problem(403, "Forbidden", "Your account is not allowed to do this."));
        }
        return null;
    }

    // ------------------------------------------------------------------------------------ shared

    private static Reply failure(Behavior behavior) {
        return switch (behavior) {
            case ERROR_500 -> Reply.json(500, problem(500, "Internal Server Error", "Boom"));
            case DROP -> Reply.drop();
            case UNAUTHORIZED -> Reply.json(401, problem(401, "Unauthorized", "Not accepted"));
            default -> null;
        };
    }

    private static Long userIdOf(Request request) {
        JWTClaimsSet claims = verifiedClaims(request);
        if (claims == null || claims.getSubject() == null) {
            return null;
        }
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JWTClaimsSet verifiedClaims(Request request) {
        String header = request.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(header.substring("Bearer ".length()));
            if (!jwt.verify(new MACVerifier(JwtTestSupport.TEST_SECRET))) {
                return null;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                return null;
            }
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> query(Request request) {
        Map<String, String> values = new LinkedHashMap<>();
        if (request.query() != null) {
            for (String pair : request.query().split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(parts[0], URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String problem(int status, String title, String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"}";
    }
}
