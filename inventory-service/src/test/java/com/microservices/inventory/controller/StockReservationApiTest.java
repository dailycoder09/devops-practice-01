package com.microservices.inventory.controller;

import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests reserve and release against the real application and a real (in-memory) database,
 * so the conditional UPDATE, the stored reservations and the audit log are all really used.
 * Every request carries a valid SERVICE token. The role rules are tested in ServiceRoleRulesTest.
 */
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
        "spring.datasource.url=jdbc:h2:mem:stock-reservation-api-test;DB_CLOSE_DELAY=-1",
        "server-info.cloud-metadata.enabled=false",
        // Nothing listens here, so these tests can never call a real product-service.
        "inventory.product-service.url=http://localhost:1"
})
@AutoConfigureMockMvc
class StockReservationApiTest {

    private static final AtomicInteger SKU_COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ helpers

    /** Creates a stock record with its own SKU, so tests never touch each other's data. */
    private String newSku(int quantityOnHand) {
        String sku = "RES-TEST-" + SKU_COUNTER.incrementAndGet();
        jdbc.update("INSERT INTO inventory_items (sku_code, quantity_on_hand) VALUES (?, ?)", sku, quantityOnHand);
        return sku;
    }

    private ResultActions reserve(String sku, int quantity, long orderId) throws Exception {
        return reserveRaw(sku, "quantity=" + quantity + "&orderId=" + orderId);
    }

    private ResultActions reserveRaw(String sku, String query) throws Exception {
        String url = "/api/inventory/sku/" + sku + "/reserve" + (query.isEmpty() ? "" : "?" + query);
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));
    }

    private ResultActions release(String sku, long orderId) throws Exception {
        return releaseRaw(sku, "orderId=" + orderId);
    }

    private ResultActions releaseRaw(String sku, String query) throws Exception {
        String url = "/api/inventory/sku/" + sku + "/release" + (query.isEmpty() ? "" : "?" + query);
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));
    }

    private int stock(String sku) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory_items WHERE sku_code = ?", Integer.class, sku);
    }

    private List<Map<String, Object>> reservations(String sku) {
        return jdbc.queryForList("SELECT * FROM stock_reservations WHERE sku_code = ? ORDER BY id", sku);
    }

    private List<Map<String, Object>> reservations(String sku, long orderId) {
        return jdbc.queryForList("SELECT * FROM stock_reservations WHERE sku_code = ? AND order_id = ?", sku, orderId);
    }

    private List<Map<String, Object>> audit(String sku, String changeType) {
        return jdbc.queryForList(
                "SELECT * FROM inventory_audit_log WHERE sku_code = ? AND change_type = ? ORDER BY id", sku, changeType);
    }

    private int auditRowCount(String sku) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_audit_log WHERE sku_code = ?", Integer.class, sku);
    }

    // ------------------------------------------------------------------ reserve: success

    @Test
    void reserve_withEnoughStock_returns200_withTheNewQuantity_andReducesStock() throws Exception {
        String sku = newSku(10);

        reserve(sku, 3, 1001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.skuCode").value(sku))
                .andExpect(jsonPath("$.quantityOnHand").value(7))
                .andExpect(jsonPath("$.reorderThreshold").value(10));

        assertThat(stock(sku)).isEqualTo(7);
    }

    @Test
    void reserve_storesTheReservationAsReserved() throws Exception {
        String sku = newSku(10);

        reserve(sku, 3, 1002).andExpect(status().isOk());

        List<Map<String, Object>> rows = reservations(sku);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("ORDER_ID", 1002L)
                .containsEntry("SKU_CODE", sku)
                .containsEntry("QUANTITY", 3)
                .containsEntry("STATUS", "RESERVED");
        assertThat(rows.get(0).get("CREATED_AT")).isNotNull();
        assertThat(rows.get(0).get("UPDATED_AT")).isNotNull();
    }

    @Test
    void reserve_writesOneAuditEntry_withOldAndNewQuantity() throws Exception {
        String sku = newSku(10);

        reserve(sku, 3, 1003).andExpect(status().isOk());

        List<Map<String, Object>> entries = audit(sku, "RESERVED");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("PREVIOUS_QUANTITY", 10).containsEntry("NEW_QUANTITY", 7);
        assertThat(auditRowCount(sku)).isEqualTo(1);
    }

    @Test
    void reserve_ofAllTheStock_leavesZero_andIsAllowed() throws Exception {
        String sku = newSku(5);

        reserve(sku, 5, 1004)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(0));

        assertThat(stock(sku)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 999})
    void reserve_atTheEdgesOfTheAllowedRange_isAccepted(int quantity) throws Exception {
        String sku = newSku(1000);

        reserve(sku, quantity, 1005).andExpect(status().isOk());

        assertThat(stock(sku)).isEqualTo(1000 - quantity);
    }

    @Test
    void reserve_isVisibleToThePublicReadEndpoint() throws Exception {
        String sku = newSku(10);

        reserve(sku, 4, 1006).andExpect(status().isOk());

        mockMvc.perform(get("/api/inventory/sku/" + sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(6));
    }

    @Test
    void reserve_forTwoOrdersOfTheSameSku_takesTheUnitsOfBoth() throws Exception {
        String sku = newSku(10);

        reserve(sku, 3, 1007).andExpect(status().isOk());
        reserve(sku, 4, 1008).andExpect(status().isOk()).andExpect(jsonPath("$.quantityOnHand").value(3));

        assertThat(stock(sku)).isEqualTo(3);
        assertThat(reservations(sku)).hasSize(2);
    }

    @Test
    void reserve_forTwoSkusOfTheSameOrder_keepsOneRowPerLine() throws Exception {
        String first = newSku(10);
        String second = newSku(10);

        reserve(first, 2, 1009).andExpect(status().isOk());
        reserve(second, 5, 1009).andExpect(status().isOk());

        assertThat(stock(first)).isEqualTo(8);
        assertThat(stock(second)).isEqualTo(5);
        assertThat(reservations(first, 1009)).hasSize(1);
        assertThat(reservations(second, 1009)).hasSize(1);
    }

    // ------------------------------------------------------------------ reserve: repeat-safe

    @Test
    void reserve_repeatedWithTheSameOrderSkuAndQuantity_answers200Again_andChangesStockOnce() throws Exception {
        String sku = newSku(10);

        reserve(sku, 3, 1010).andExpect(status().isOk());
        reserve(sku, 3, 1010).andExpect(status().isOk()).andExpect(jsonPath("$.quantityOnHand").value(7));
        reserve(sku, 3, 1010).andExpect(status().isOk()).andExpect(jsonPath("$.quantityOnHand").value(7));

        assertThat(stock(sku)).isEqualTo(7);
        assertThat(reservations(sku, 1010)).hasSize(1);
        assertThat(audit(sku, "RESERVED")).hasSize(1);
    }

    @Test
    void reserve_repeatedWhenStockIsNowTooLowForANewReserve_stillAnswers200() throws Exception {
        // The repeat must be recognised before any stock check: 2 left, the same order asks again for 3.
        String sku = newSku(5);

        reserve(sku, 3, 1011).andExpect(status().isOk());
        reserve(sku, 3, 1011).andExpect(status().isOk());

        assertThat(stock(sku)).isEqualTo(2);
    }

    @Test
    void reserve_sameOrderAndSkuWithAnotherQuantity_returns409_andChangesNothing() throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 1012).andExpect(status().isOk());

        reserve(sku, 4, 1012)
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(stock(sku)).isEqualTo(7);
        assertThat(reservations(sku, 1012)).hasSize(1);
        assertThat(reservations(sku, 1012).get(0)).containsEntry("QUANTITY", 3).containsEntry("STATUS", "RESERVED");
        assertThat(audit(sku, "RESERVED")).hasSize(1);
    }

    @Test
    void reserve_ofALineThatWasReleased_returns409_andChangesNothing() throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 1013).andExpect(status().isOk());
        release(sku, 1013).andExpect(status().isOk());

        reserve(sku, 3, 1013)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(stock(sku)).isEqualTo(10);
        assertThat(reservations(sku, 1013).get(0)).containsEntry("STATUS", "RELEASED");
        assertThat(audit(sku, "RESERVED")).hasSize(1);
    }

    // ------------------------------------------------------------------ reserve: refused

    @Test
    void reserve_withNotEnoughStock_returns409_withWantedAndAvailable_andChangesNothing() throws Exception {
        String sku = newSku(2);

        reserve(sku, 3, 1020)
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("Not enough stock for SKU " + sku + ": wanted 3, available 2"));

        assertThat(stock(sku)).isEqualTo(2);
        assertThat(reservations(sku)).isEmpty();
        assertThat(auditRowCount(sku)).isZero();
    }

    @Test
    void reserve_ofASkuWithNoStockAtAll_returns409() throws Exception {
        String sku = newSku(0);

        reserve(sku, 1, 1021)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough stock for SKU " + sku + ": wanted 1, available 0"));

        assertThat(stock(sku)).isZero();
    }

    @Test
    void reserve_ofAnUnknownSku_returns404_andStoresNothing() throws Exception {
        reserve("NO-SUCH-SKU-FOR-RESERVE", 1, 1022)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(reservations("NO-SUCH-SKU-FOR-RESERVE")).isEmpty();
    }

    // ------------------------------------------------------------------ reserve: bad parameters (Review Focus 2)

    @ParameterizedTest(name = "reserve with ?{0} gives 400")
    @ValueSource(strings = {
            "quantity=0&orderId=5",
            "quantity=1000&orderId=5",
            "quantity=-1&orderId=5",
            "quantity=-2147483648&orderId=5",
            "quantity=abc&orderId=5",
            "quantity=&orderId=5",
            "quantity=1.5&orderId=5",
            "quantity=2147483648&orderId=5",
            "quantity=99999999999999999999999&orderId=5",
            "orderId=5",
            "quantity=3&orderId=0",
            "quantity=3&orderId=-1",
            "quantity=3&orderId=abc",
            "quantity=3&orderId=",
            "quantity=3&orderId=99999999999999999999999",
            "quantity=3",
            ""
    })
    void reserve_withBadParameters_returns400_andChangesNothing(String query) throws Exception {
        String sku = newSku(10);

        reserveRaw(sku, query)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(stock(sku)).isEqualTo(10);
        assertThat(reservations(sku)).isEmpty();
        assertThat(auditRowCount(sku)).isZero();
    }

    @Test
    void reserve_withBadParameters_onAnUnknownSku_stillAnswers400() throws Exception {
        reserveRaw("NO-SUCH-SKU-FOR-RESERVE", "quantity=0&orderId=5").andExpect(status().isBadRequest());
    }

    @Test
    void reserve_withTheHighestOrderId_works() throws Exception {
        String sku = newSku(10);

        reserve(sku, 1, Long.MAX_VALUE).andExpect(status().isOk());

        assertThat(reservations(sku, Long.MAX_VALUE)).hasSize(1);
    }

    // ------------------------------------------------------------------ release

    @Test
    void release_ofAReservedLine_addsTheQuantityBack_andReturnsTheCurrentStock() throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 2001).andExpect(status().isOk());

        release(sku, 2001)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuCode").value(sku))
                .andExpect(jsonPath("$.quantityOnHand").value(10));

        assertThat(stock(sku)).isEqualTo(10);
    }

    @Test
    void release_marksTheReservationReleased_andWritesOneAuditEntry() throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 2002).andExpect(status().isOk());

        release(sku, 2002).andExpect(status().isOk());

        assertThat(reservations(sku, 2002)).hasSize(1);
        assertThat(reservations(sku, 2002).get(0)).containsEntry("STATUS", "RELEASED").containsEntry("QUANTITY", 3);
        List<Map<String, Object>> entries = audit(sku, "RELEASED");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("PREVIOUS_QUANTITY", 7).containsEntry("NEW_QUANTITY", 10);
    }

    @Test
    void release_repeated_answers200Again_andChangesStockOnce() throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 2003).andExpect(status().isOk());

        release(sku, 2003).andExpect(status().isOk());
        release(sku, 2003).andExpect(status().isOk()).andExpect(jsonPath("$.quantityOnHand").value(10));
        release(sku, 2003).andExpect(status().isOk()).andExpect(jsonPath("$.quantityOnHand").value(10));

        assertThat(stock(sku)).isEqualTo(10);
        assertThat(audit(sku, "RELEASED")).hasSize(1);
    }

    @Test
    void release_whenNothingWasReserved_answers200_andChangesNothing() throws Exception {
        String sku = newSku(10);

        release(sku, 2004)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(10));

        assertThat(stock(sku)).isEqualTo(10);
        assertThat(reservations(sku)).isEmpty();
        assertThat(auditRowCount(sku)).isZero();
    }

    @Test
    void release_ofAnUnknownSku_returns404() throws Exception {
        release("NO-SUCH-SKU-FOR-RELEASE", 2005)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @ParameterizedTest(name = "release with ?{0} gives 400")
    @ValueSource(strings = {
            "orderId=0",
            "orderId=-1",
            "orderId=abc",
            "orderId=",
            "orderId=1.5",
            "orderId=99999999999999999999999",
            ""
    })
    void release_withBadOrderId_returns400_andChangesNothing(String query) throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 2006).andExpect(status().isOk());

        releaseRaw(sku, query)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(stock(sku)).isEqualTo(7);
        assertThat(reservations(sku, 2006).get(0)).containsEntry("STATUS", "RESERVED");
    }

    @Test
    void release_withBadOrderId_onAnUnknownSku_stillAnswers400() throws Exception {
        releaseRaw("NO-SUCH-SKU-FOR-RELEASE", "orderId=0").andExpect(status().isBadRequest());
    }

    @Test
    void release_touchesOnlyTheGivenOrder() throws Exception {
        String sku = newSku(10);
        reserve(sku, 3, 2007).andExpect(status().isOk());
        reserve(sku, 2, 2008).andExpect(status().isOk());

        release(sku, 2007).andExpect(status().isOk()).andExpect(jsonPath("$.quantityOnHand").value(8));

        assertThat(reservations(sku, 2007).get(0)).containsEntry("STATUS", "RELEASED");
        assertThat(reservations(sku, 2008).get(0)).containsEntry("STATUS", "RESERVED");
    }

    @Test
    void release_touchesOnlyTheGivenSkuOfAnOrder() throws Exception {
        String first = newSku(10);
        String second = newSku(10);
        reserve(first, 3, 2009).andExpect(status().isOk());
        reserve(second, 4, 2009).andExpect(status().isOk());

        release(first, 2009).andExpect(status().isOk());

        assertThat(stock(first)).isEqualTo(10);
        assertThat(stock(second)).isEqualTo(6);
        assertThat(reservations(second, 2009).get(0)).containsEntry("STATUS", "RESERVED");
    }

    @Test
    void release_ofEveryLineOfAnOrder_afterOneLineWasNeverReserved_isSafe() throws Exception {
        // This is what order-service does after a refused reserve: release every line, reserved or not.
        String reservedLine = newSku(10);
        String refusedLine = newSku(1);
        reserve(reservedLine, 3, 2010).andExpect(status().isOk());
        reserve(refusedLine, 3, 2010).andExpect(status().isConflict());

        release(reservedLine, 2010).andExpect(status().isOk());
        release(refusedLine, 2010).andExpect(status().isOk());

        assertThat(stock(reservedLine)).isEqualTo(10);
        assertThat(stock(refusedLine)).isEqualTo(1);
    }

    @Test
    void reserveAgain_afterReleasingSomethingNeverReserved_isStillAllowed() throws Exception {
        // A release with no row stores nothing, so a late reserve of that order can still happen.
        String sku = newSku(10);
        release(sku, 2011).andExpect(status().isOk());

        reserve(sku, 3, 2011).andExpect(status().isOk());

        assertThat(stock(sku)).isEqualTo(7);
    }

    @Test
    void reserveThenRelease_leavesStockWhereItStarted_afterManyRounds() throws Exception {
        String sku = newSku(10);

        for (long orderId = 3000; orderId < 3010; orderId++) {
            reserve(sku, 10, orderId).andExpect(status().isOk());
            assertThat(stock(sku)).isZero();
            release(sku, orderId).andExpect(status().isOk());
            assertThat(stock(sku)).isEqualTo(10);
        }
    }
}
