package com.microservices.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.order.service.ServiceTokenIssuer;
import com.microservices.order.support.CheckoutTestBase;
import com.microservices.order.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two admin-only read endpoints of the admin area, GET /api/orders/all and GET /api/orders/stats, and the two new
 * fields of the order JSON (userId and userEmail): role rules for no token, USER, SERVICE and ADMIN, the status
 * filter, the limits, the order, the page shape, the numbers of the stats, and that the literal paths do not clash
 * with GET /api/orders/{id}.
 *
 * Every test starts with an empty orders table, so counts and lists can be exact.
 */
class AdminOrdersTest extends CheckoutTestBase {

    private static final long ADMIN_ID = 9_001;

    @Autowired
    private ServiceTokenIssuer serviceTokenIssuer;

    private final String adminToken = JwtTestSupport.adminToken(ADMIN_ID);

    @BeforeEach
    void emptyTheOrdersTable() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    // ---------------------------------------------------------------------------- data

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 11, 1, 10, 0, 0);

    /** Inserts an order with one line directly, with a chosen status, total and creation time. Returns its id. */
    private long seed(long forUser, String email, String status, String total, LocalDateTime createdAt) {
        Timestamp created = Timestamp.valueOf(createdAt);
        jdbc.update("INSERT INTO orders (user_id, user_email, status, total, reject_reason, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", forUser, email, status, new BigDecimal(total),
                "REJECTED".equals(status) ? "Not enough stock for SKU-A: wanted 3, available 1" : null, created, created);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM orders", Long.class);
        jdbc.update("INSERT INTO order_items (order_id, sku_code, name, unit_price, quantity) "
                + "VALUES (?, 'SKU-A', 'Phone A', 10.00, 1)", id);
        return id;
    }

    private long seed(long forUser, String status, String total, int minutesAfterBase) {
        return seed(forUser, "u" + forUser + "@example.com", status, total, BASE_TIME.plusMinutes(minutesAfterBase));
    }

    private MockHttpServletRequestBuilder asAdmin(String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, bearer(adminToken));
    }

    private JsonNode allOrders(String query) throws Exception {
        return json(mockMvc.perform(asAdmin("/api/orders/all" + query)).andExpect(status().isOk()));
    }

    private JsonNode stats() throws Exception {
        return json(mockMvc.perform(asAdmin("/api/orders/stats")).andExpect(status().isOk()));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(order -> ids.add(order.get("id").asLong()));
        return ids;
    }

    // ================================================================== role rules (both endpoints)

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders/all", "/api/orders/stats"})
    void noToken_is401_asProblemJson(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders/all", "/api/orders/stats"})
    void aUserToken_is403_notA400ForANonNumericId(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders/all", "/api/orders/stats"})
    void aSERVICEToken_is403(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(serviceTokenIssuer.issue())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertThat(shop.cartServer.requests()).isEmpty();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders/all", "/api/orders/stats"})
    void anAdminToken_is200(String path) throws Exception {
        mockMvc.perform(asAdmin(path)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GUEST", "user", "admin", ""})
    void anotherRole_isForbidden_onBothEndpoints(String role) throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, "5", "a@b.c", role);
        for (String path : List.of("/api/orders/all", "/api/orders/stats")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isForbidden());
        }
    }

    @Test
    void aTokenWithoutARole_isForbidden() throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, "5", "a@b.c", null);
        for (String path : List.of("/api/orders/all", "/api/orders/stats")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isForbidden());
        }
    }

    @Test
    void badTokens_are401_onBothEndpoints() throws Exception {
        Instant now = Instant.now();
        List<String> badTokens = List.of(
                JwtTestSupport.token(JwtTestSupport.TEST_SECRET, "1", "a@b.c", "ADMIN", now.minusSeconds(7200),
                        now.minusSeconds(3600)),
                JwtTestSupport.validToken("another-secret-that-is-long-enough-for-hs256-0123456789", "1", "a@b.c", "ADMIN"),
                JwtTestSupport.withTamperedRole(userToken, "ADMIN"),
                JwtTestSupport.unsigned("1", "a@b.c", "ADMIN"),
                "garbage");
        for (String token : badTokens) {
            for (String path : List.of("/api/orders/all", "/api/orders/stats")) {
                mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "order-service", "1.5", "99999999999999999999999"})
    void anAdminTokenWhoseSubjectIsNotANumber_is401_likeEveryOtherOrderCall(String subject) throws Exception {
        String token = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, subject, "a@b.c", "ADMIN");
        for (String path : List.of("/api/orders/all", "/api/orders/stats")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders/all", "/api/orders/stats"})
    void otherMethodsOnTheLiteralPaths_followTheSameRoleRules(String path) throws Exception {
        List<MockHttpServletRequestBuilder> calls = List.of(post(path), put(path), delete(path));
        for (MockHttpServletRequestBuilder call : calls) {
            // no token: 401, USER: 403 (never the 405 or the checkout of POST /api/orders)
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
        for (var call : List.of(post(path), put(path), delete(path))) {
            mockMvc.perform(call.header(HttpHeaders.AUTHORIZATION, bearer(userToken))).andExpect(status().isForbidden());
        }
        for (var call : List.of(post(path), put(path), delete(path))) {
            mockMvc.perform(call.header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isMethodNotAllowed());
        }
        assertThat(shop.cartServer.requests()).as("no checkout was started").isEmpty();
    }

    @Test
    void aPathLikeAllWithAnotherCase_orWithATrailingSlash_isNotTheAdminListAndNeverAnOrder() throws Exception {
        for (String path : List.of("/api/orders/ALL", "/api/orders/Stats", "/api/orders/all/", "/api/orders/all/x")) {
            int userStatus = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                    .andReturn().getResponse().getStatus();
            assertThat(userStatus).as(path + " for a USER").isIn(400, 403, 404);
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // ================================================================== no clash with /{id}

    @Test
    void theLiteralPaths_doNotClashWith_theNumericIdPath() throws Exception {
        long id = placeOrder(userId, "SKU-A", 1);

        // The numeric id still reads one order, for the owner and for an admin.
        getOrder(userToken, id).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.content").doesNotExist());
        getOrder(adminToken, id).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // The literal paths are the admin list and the stats, not an order id.
        mockMvc.perform(asAdmin("/api/orders/all")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray()).andExpect(jsonPath("$.id").doesNotExist());
        mockMvc.perform(asAdmin("/api/orders/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").exists()).andExpect(jsonPath("$.id").doesNotExist());
        // Anything else that is not a number is still a 400 for a person who may read orders.
        getOrder(adminToken, "everything").andExpect(status().isBadRequest());
        getOrder(userToken, "all-orders").andExpect(status().isBadRequest());
    }

    @Test
    void getOrdersWithoutALiteralPath_isStillTheCallersOwnList_evenForAnAdmin() throws Exception {
        seed(1, "CONFIRMED", "10.00", 1);
        seed(ADMIN_ID, "CONFIRMED", "20.00", 2);

        JsonNode own = json(mockMvc.perform(asAdmin("/api/orders")).andExpect(status().isOk()));

        assertThat(own.get("totalElements").asLong()).isEqualTo(1);
        assertThat(own.get("content").get(0).get("userId").asLong()).isEqualTo(ADMIN_ID);
    }

    // ================================================================== GET /api/orders/all

    @Test
    void all_listsTheOrdersOfEveryUser_withTheirLines() throws Exception {
        long first = seed(11, "CONFIRMED", "10.00", 1);
        long second = seed(22, "REJECTED", "20.00", 2);
        long third = seed(33, "PENDING", "30.00", 3);

        JsonNode page = allOrders("");

        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(page)).containsExactly(third, second, first);
        JsonNode rejected = page.get("content").get(1);
        assertThat(rejected.get("userId").asLong()).isEqualTo(22);
        assertThat(rejected.get("userEmail").asText()).isEqualTo("u22@example.com");
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectReason").asText()).startsWith("Not enough stock");
        assertThat(rejected.get("total").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(rejected.get("items")).hasSize(1);
        assertThat(rejected.get("items").get(0).get("sku").asText()).isEqualTo("SKU-A");
        assertThat(rejected.has("cartCleared")).isFalse();
    }

    @Test
    void all_isNewestFirst_byCreationTime_notByInsertOrder_andTiesGoByHigherIdFirst() throws Exception {
        // Inserted in this order, so the ids are 1, 2, 3, 4 - but the creation times say otherwise.
        long newest = seed(1, "CONFIRMED", "1.00", 50);
        long oldest = seed(2, "CONFIRMED", "2.00", 10);
        long tieLowId = seed(3, "CONFIRMED", "3.00", 30);
        long tieHighId = seed(4, "CONFIRMED", "4.00", 30);

        JsonNode page = allOrders("");

        assertThat(ids(page)).containsExactly(newest, tieHighId, tieLowId, oldest);
    }

    @Test
    void all_hasTheSamePageShapeAsTheOtherLists() throws Exception {
        seed(1, "CONFIRMED", "1.00", 1);

        JsonNode page = allOrders("");

        assertThat(page.fieldNames()).toIterable().contains("content", "totalElements", "totalPages", "number", "size",
                "numberOfElements", "first", "last", "empty");
        assertThat(page.get("number").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("numberOfElements").asInt()).isEqualTo(1);
        assertThat(page.get("first").asBoolean()).isTrue();
        assertThat(page.get("last").asBoolean()).isTrue();
        assertThat(page.get("empty").asBoolean()).isFalse();
    }

    @Test
    void all_whenThereAreNoOrders_isAnEmptyPage() throws Exception {
        JsonNode page = allOrders("");

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
        assertThat(page.get("empty").asBoolean()).isTrue();
    }

    @Test
    void all_isPaged() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            created.add(seed(i, "CONFIRMED", "1.00", i));
        }

        JsonNode pageOne = allOrders("?page=0&size=2");
        JsonNode pageTwo = allOrders("?page=1&size=2");
        JsonNode pageThree = allOrders("?page=2&size=2");

        assertThat(pageOne.get("totalElements").asLong()).isEqualTo(5);
        assertThat(pageOne.get("totalPages").asInt()).isEqualTo(3);
        assertThat(pageOne.get("size").asInt()).isEqualTo(2);
        assertThat(ids(pageOne)).containsExactly(created.get(4), created.get(3));
        assertThat(pageTwo.get("number").asInt()).isEqualTo(1);
        assertThat(ids(pageTwo)).containsExactly(created.get(2), created.get(1));
        assertThat(ids(pageThree)).containsExactly(created.get(0));
        assertThat(pageThree.get("last").asBoolean()).isTrue();
    }

    @Test
    void all_aPageFarPastTheEnd_isEmpty_notAnError() throws Exception {
        seed(1, "CONFIRMED", "1.00", 1);

        assertThat(allOrders("?page=50&size=100").get("content")).isEmpty();
        assertThat(allOrders("?page=100000&size=100").get("content")).isEmpty();
    }

    @Test
    void all_sizeOneAndSizeHundred_areAllowed() throws Exception {
        seed(1, "CONFIRMED", "1.00", 1);
        seed(2, "CONFIRMED", "1.00", 2);

        assertThat(allOrders("?size=1").get("content")).hasSize(1);
        assertThat(allOrders("?size=100").get("content")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=-5", "?size=101", "?size=abc", "?page=x", "?size=99999999999",
            "?page=100001", "?page=2147483647", "?page=2147483647&size=100"})
    void all_withBadPaging_is400_asProblemJson(String query) throws Exception {
        mockMvc.perform(asAdmin("/api/orders/all" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void all_ignoresASortParameter_orderStaysNewestFirst() throws Exception {
        long first = seed(1, "CONFIRMED", "1.00", 1);
        long second = seed(2, "CONFIRMED", "1.00", 2);

        assertThat(ids(allOrders("?sort=id,asc"))).containsExactly(second, first);
        assertThat(ids(allOrders("?sort=total,desc"))).containsExactly(second, first);
    }

    // ---------------------------------------------------------------------------- the status filter

    @Test
    void all_filtersByEachStatus() throws Exception {
        long confirmedOne = seed(1, "CONFIRMED", "10.00", 1);
        long pending = seed(2, "PENDING", "20.00", 2);
        long rejected = seed(3, "REJECTED", "30.00", 3);
        long confirmedTwo = seed(4, "CONFIRMED", "40.00", 4);

        JsonNode confirmedPage = allOrders("?status=CONFIRMED");
        JsonNode pendingPage = allOrders("?status=PENDING");
        JsonNode rejectedPage = allOrders("?status=REJECTED");

        assertThat(ids(confirmedPage)).containsExactly(confirmedTwo, confirmedOne);
        assertThat(confirmedPage.get("totalElements").asLong()).isEqualTo(2);
        assertThat(ids(pendingPage)).containsExactly(pending);
        assertThat(ids(rejectedPage)).containsExactly(rejected);
        confirmedPage.get("content").forEach(order -> assertThat(order.get("status").asText()).isEqualTo("CONFIRMED"));
    }

    @Test
    void all_theFilterIsPagedAndCounted_forTheFilteredSet() throws Exception {
        List<Long> confirmed = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            confirmed.add(seed(i, "CONFIRMED", "1.00", i * 2));
            seed(i + 100, "REJECTED", "1.00", i * 2 + 1);
        }

        JsonNode page = allOrders("?status=CONFIRMED&size=2&page=1");

        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(page)).containsExactly(confirmed.get(0));
    }

    @Test
    void all_aStatusWithoutOrders_isAnEmptyPage() throws Exception {
        seed(1, "CONFIRMED", "1.00", 1);

        JsonNode page = allOrders("?status=PENDING");

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?status=SHIPPED", "?status=confirmed", "?status=Pending", "?status=1", "?status=CONFIRMED,PENDING",
            "?status=%27%3BDROP", "?status=ALL"})
    void all_anUnknownStatus_is400_asProblemJson(String query) throws Exception {
        mockMvc.perform(asAdmin("/api/orders/all" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void all_anEmptyStatus_meansNoFilter() throws Exception {
        long confirmed = seed(1, "CONFIRMED", "1.00", 1);
        long rejected = seed(2, "REJECTED", "1.00", 2);

        // The admin screen may send ?status= when no filter is chosen.
        assertThat(ids(allOrders("?page=0&size=20&status="))).containsExactly(rejected, confirmed);
    }

    // ================================================================== the new fields in the order JSON

    @Test
    void theOrderJson_hasUserIdAsANumber_andUserEmail_inEveryAnswer() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        JsonNode posted = json(checkout(userToken).andExpect(status().isCreated()));
        long orderId = posted.get("id").asLong();
        JsonNode one = json(getOrder(userToken, orderId).andExpect(status().isOk()));
        JsonNode listed = json(mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isOk())).get("content").get(0);
        JsonNode inAdminList = allOrders("").get("content").get(0);

        for (JsonNode order : List.of(posted, one, listed, inAdminList)) {
            assertThat(order.get("userId").isIntegralNumber()).as("userId is a JSON number").isTrue();
            assertThat(order.get("userId").asLong()).isEqualTo(userId);
            assertThat(order.get("userEmail").asText()).isEqualTo("user" + userId + "@example.com");
            assertThat(order.get("id").asLong()).isEqualTo(orderId);
        }
    }

    @Test
    void theOrderJson_hasUserIdAndEmail_alsoForARejectedOrder_andWhenAnAdminReadsAnotherUsersOrder() throws Exception {
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        long orderId = json(checkout(userToken).andExpect(status().isConflict())).get("orderId").asLong();

        JsonNode asAdmin = json(getOrder(adminToken, orderId).andExpect(status().isOk()));

        assertThat(asAdmin.get("status").asText()).isEqualTo("REJECTED");
        assertThat(asAdmin.get("userId").asLong()).isEqualTo(userId);
        assertThat(asAdmin.get("userEmail").asText()).isEqualTo("user" + userId + "@example.com");
    }

    @Test
    void anOrderWithoutAnEmail_leavesUserEmailOut_asEveryNullProperty_butStillHasUserId() throws Exception {
        String noEmailToken = JwtTestSupport.validToken(JwtTestSupport.TEST_SECRET, String.valueOf(userId), null, "USER");
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        JsonNode posted = json(checkout(noEmailToken).andExpect(status().isCreated()));
        JsonNode inAdminList = allOrders("").get("content").get(0);

        for (JsonNode order : List.of(posted, inAdminList)) {
            assertThat(order.get("userId").asLong()).isEqualTo(userId);
            assertThat(order.has("userEmail")).isFalse();
        }
    }

    @Test
    void anotherUser_stillSeesHisOwnFields_inTheOwnList() throws Exception {
        long other = userId + 4000;
        placeOrder(userId, "SKU-C", 1);
        placeOrder(other, "SKU-C", 1);

        JsonNode otherList = json(mockMvc.perform(get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(JwtTestSupport.userToken(other)))).andExpect(status().isOk()));

        assertThat(otherList.get("content")).hasSize(1);
        assertThat(otherList.get("content").get(0).get("userId").asLong()).isEqualTo(other);
        assertThat(allOrders("").get("totalElements").asLong()).isEqualTo(2);
    }

    // ================================================================== GET /api/orders/stats

    @Test
    void stats_withoutOrders_areAllZero_andRevenueHasTwoDecimals() throws Exception {
        String body = mockMvc.perform(asAdmin("/api/orders/stats")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode stats = objectMapper.readTree(body);
        assertThat(stats.get("totalOrders").asLong()).isZero();
        assertThat(stats.get("pending").asLong()).isZero();
        assertThat(stats.get("confirmed").asLong()).isZero();
        assertThat(stats.get("rejected").asLong()).isZero();
        assertThat(stats.get("revenue").isNumber()).isTrue();
        assertThat(body).contains("\"revenue\":0.00");
    }

    @Test
    void stats_countEveryStatus_andRevenueIsTheSumOfTheConfirmedTotalsOnly() throws Exception {
        seed(1, "CONFIRMED", "100.00", 1);
        seed(2, "CONFIRMED", "61.50", 2);
        seed(3, "PENDING", "5.00", 3);
        seed(4, "PENDING", "6.00", 4);
        seed(5, "REJECTED", "999.00", 5);

        String body = mockMvc.perform(asAdmin("/api/orders/stats")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode stats = objectMapper.readTree(body);

        assertThat(stats.get("totalOrders").asLong()).isEqualTo(5);
        assertThat(stats.get("pending").asLong()).isEqualTo(2);
        assertThat(stats.get("confirmed").asLong()).isEqualTo(2);
        assertThat(stats.get("rejected").asLong()).isEqualTo(1);
        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo("161.50");
        assertThat(body).contains("\"revenue\":161.50");
    }

    @Test
    void stats_haveExactlyTheContractFields_withNumbersNotStrings() throws Exception {
        seed(1, "CONFIRMED", "10.00", 1);

        JsonNode stats = stats();

        assertThat(stats.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("totalOrders", "pending", "confirmed", "rejected", "revenue");
        stats.fields().forEachRemaining(field -> assertThat(field.getValue().isNumber()).as(field.getKey()).isTrue());
        assertThat(stats.get("totalOrders").isIntegralNumber()).isTrue();
    }

    @Test
    void stats_addUpMoneyExactly_noRoundingErrors() throws Exception {
        seed(1, "CONFIRMED", "0.10", 1);
        seed(2, "CONFIRMED", "0.20", 2);
        seed(3, "CONFIRMED", "9999999999.99", 3);

        JsonNode stats = stats();

        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo("10000000000.29");
    }

    @Test
    void stats_followRealOrders_fromTheCheckout() throws Exception {
        long other = userId + 6000;
        shop.addToCart(userId, "SKU-A", 2, "100.00");
        checkout(userToken).andExpect(status().isCreated());
        shop.addToCart(other, "SKU-B", 1, "20.50");
        checkout(JwtTestSupport.userToken(other)).andExpect(status().isCreated());
        shop.addToCart(userId, "SKU-B", 9, "20.50");
        checkout(userToken).andExpect(status().isConflict());

        JsonNode stats = stats();

        assertThat(stats.get("totalOrders").asLong()).isEqualTo(3);
        assertThat(stats.get("confirmed").asLong()).isEqualTo(2);
        assertThat(stats.get("rejected").asLong()).isEqualTo(1);
        assertThat(stats.get("pending").asLong()).isZero();
        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo("220.50");
        // Cross-check with the database itself.
        assertThat(stats.get("totalOrders").asLong()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
        assertThat(stats.get("revenue").decimalValue()).isEqualByComparingTo(jdbc.queryForObject(
                "SELECT SUM(total) FROM orders WHERE status = 'CONFIRMED'", BigDecimal.class));
    }

    @Test
    void stats_changeWhenAnOrderChangesStatus() throws Exception {
        long id = seed(1, "PENDING", "50.00", 1);
        assertThat(stats().get("pending").asLong()).isEqualTo(1);
        assertThat(stats().get("revenue").decimalValue()).isEqualByComparingTo("0.00");

        jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", id);

        JsonNode after = stats();
        assertThat(after.get("pending").asLong()).isZero();
        assertThat(after.get("confirmed").asLong()).isEqualTo(1);
        assertThat(after.get("revenue").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(after.get("totalOrders").asLong()).isEqualTo(1);
    }

    @Test
    void stats_countOrdersOfAllUsers_notOnlyTheCallers() throws Exception {
        seed(ADMIN_ID, "CONFIRMED", "10.00", 1);
        seed(1, "CONFIRMED", "10.00", 2);
        seed(2, "CONFIRMED", "10.00", 3);

        assertThat(stats().get("confirmed").asLong()).isEqualTo(3);
    }

    @Test
    void stats_ignoreQueryParameters() throws Exception {
        seed(1, "CONFIRMED", "10.00", 1);

        JsonNode stats = json(mockMvc.perform(asAdmin("/api/orders/stats?status=REJECTED&page=7&size=0"))
                .andExpect(status().isOk()));

        assertThat(stats.get("confirmed").asLong()).isEqualTo(1);
    }

    @Test
    void theAdminReads_neverCallAnotherService() throws Exception {
        seed(1, "CONFIRMED", "10.00", 1);

        allOrders("");
        stats();

        assertThat(shop.cartServer.requests()).isEmpty();
        assertThat(shop.productServer.requests()).isEmpty();
        assertThat(shop.inventoryServer.requests()).isEmpty();
    }
}
