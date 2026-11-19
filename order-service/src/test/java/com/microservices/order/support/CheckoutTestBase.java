package com.microservices.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared set-up for the tests that run the whole service (real security chain, real database, real HTTP calls)
 * against the fake cart, product and inventory services of {@link FakeShop}.
 *
 * Every test starts with three products that have stock, an empty cart and a new user id,
 * so tests never see each other's orders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class CheckoutTestBase {

    protected static final FakeShop shop = FakeShop.get();

    private static final AtomicLong NEXT_USER_ID = new AtomicLong(1000);

    @DynamicPropertySource
    protected static void fakeServiceUrls(DynamicPropertyRegistry registry) {
        registry.add("order.cart-service.url", shop.cartServer::baseUrl);
        registry.add("order.product-service.url", shop.productServer::baseUrl);
        registry.add("order.inventory-service.url", shop.inventoryServer::baseUrl);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** The user of this test and a valid USER token for them. */
    protected long userId;
    protected String userToken;

    @BeforeEach
    protected void setUpFakeShop() {
        shop.reset();
        shop.addProduct("SKU-A", "Phone A", "100.00");
        shop.addStock("SKU-A", 10);
        shop.addProduct("SKU-B", "Case B", "20.50");
        shop.addStock("SKU-B", 5);
        shop.addProduct("SKU-C", "Cable C", "5.00");
        shop.addStock("SKU-C", 8);

        userId = NEXT_USER_ID.incrementAndGet();
        userToken = JwtTestSupport.userToken(userId);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /** POST /api/orders with this token. */
    protected ResultActions checkout(String token) throws Exception {
        return mockMvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected ResultActions getOrder(String token, Object id) throws Exception {
        return mockMvc.perform(get("/api/orders/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Puts the user's cart in the fake cart service and checks out; returns the id of the new order. */
    protected long placeOrder(long forUser, String sku, int quantity) throws Exception {
        shop.addToCart(forUser, sku, quantity, "1.00");
        return json(checkout(JwtTestSupport.userToken(forUser))).get("id").asLong();
    }

    // ---------------------------------------------------------------------------- what is in the database

    protected long orderCount(long forUser) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, forUser);
    }

    protected String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    protected String rejectReasonOf(long orderId) {
        return jdbc.queryForObject("SELECT reject_reason FROM orders WHERE id = ?", String.class, orderId);
    }

    protected long lineCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Long.class, orderId);
    }

    // ---------------------------------------------------------------------------- what the fakes saw

    protected static List<FakeHttpServer.Request> reserveCalls() {
        return shop.inventoryServer.requests().stream().filter(r -> r.path().endsWith("/reserve")).toList();
    }

    protected static List<FakeHttpServer.Request> releaseCalls() {
        return shop.inventoryServer.requests().stream().filter(r -> r.path().endsWith("/release")).toList();
    }

    /** The SKU in a path like /api/inventory/sku/SKU-A/reserve. */
    protected static String skuOf(FakeHttpServer.Request request) {
        String[] parts = request.path().split("/");
        return parts[parts.length - 2];
    }

    /** The claims of a token, after checking its signature with the test secret. */
    protected static JWTClaimsSet verifiedClaims(String authorizationHeader) throws Exception {
        SignedJWT jwt = SignedJWT.parse(authorizationHeader.substring("Bearer ".length()));
        if (!jwt.verify(new MACVerifier(JwtTestSupport.TEST_SECRET))) {
            throw new AssertionError("The signature of the token is not valid");
        }
        return jwt.getJWTClaimsSet();
    }
}
