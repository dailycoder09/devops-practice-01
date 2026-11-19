package com.microservices.inventory.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Test 7: the audit events of inventory-service, with their exact names and fields. The person (or service)
 * that did it comes from the MDC of the request. An event is written once per action, and not at all when the
 * action fails.
 */
class InventoryAuditEventsTest extends AbstractLoggingIT {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String ADMIN = JwtTestSupport.bearerFor("9", "boss@example.com", "ADMIN");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String newSku() {
        return "AUD-" + COUNTER.incrementAndGet() + "-" + (System.nanoTime() % 100000);
    }

    private long insertItem(String sku, int quantity) {
        jdbc.update("INSERT INTO inventory_items (sku_code, quantity_on_hand) VALUES (?, ?)", sku, quantity);
        return jdbc.queryForObject("SELECT id FROM inventory_items WHERE sku_code = ?", Long.class, sku);
    }

    private void assertAdminAudit(LogCapture.Line line, String event, String sku) {
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.logger()).isEqualTo("audit");
        assertThat(line.text("event")).isEqualTo(event);
        assertThat(line.text("sku")).isEqualTo(sku);
        assertThat(line.text("userId")).isEqualTo("9");
        assertThat(line.text("userEmail")).isEqualTo("boss@example.com");
        assertThat(line.text("userRole")).isEqualTo("ADMIN");
        assertThat(line.text("traceId")).isNotBlank();
        assertThat(line.message()).isNotBlank();
    }

    private void assertServiceAudit(LogCapture.Line line, String event, String sku, long orderId) {
        assertThat(line.logger()).isEqualTo("audit");
        assertThat(line.text("event")).isEqualTo(event);
        assertThat(line.text("sku")).isEqualTo(sku);
        assertThat(line.number("orderId")).isEqualTo(orderId);
        assertThat(line.text("userId")).isEqualTo("order-service");
        assertThat(line.text("userRole")).isEqualTo("SERVICE");
    }

    private void reserve(String sku, int quantity, long orderId) throws Exception {
        mockMvc.perform(post("/api/inventory/sku/{sku}/reserve", sku).param("quantity", String.valueOf(quantity))
                .param("orderId", String.valueOf(orderId)).header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));
    }

    private void release(String sku, long orderId) throws Exception {
        mockMvc.perform(post("/api/inventory/sku/{sku}/release", sku).param("orderId", String.valueOf(orderId))
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));
    }

    // ------------------------------------------------------------------ admin changes

    @Test
    void adjustStock_writesStockAdjusted_once() throws Exception {
        String sku = newSku();
        insertItem(sku, 10);

        mockMvc.perform(patch("/api/inventory/sku/{sku}/adjust", sku).param("delta", "-3")
                .header(HttpHeaders.AUTHORIZATION, ADMIN));

        var events = capture.withEvent("stock_adjusted");
        assertThat(events).hasSize(1);
        assertAdminAudit(events.get(0), "stock_adjusted", sku);
        assertThat(events.get(0).number("delta")).isEqualTo(-3);
        assertThat(events.get(0).number("before")).isEqualTo(10);
        assertThat(events.get(0).number("after")).isEqualTo(7);
    }

    @Test
    void createInventory_writesInventoryCreated_once() throws Exception {
        String sku = newSku();

        MvcResult result = mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN)
                .content("{\"skuCode\":\"" + sku + "\",\"quantityOnHand\":4}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var events = capture.withEvent("inventory_created");
        assertThat(events).hasSize(1);
        assertAdminAudit(events.get(0), "inventory_created", sku);
        assertThat(events.get(0).number("quantity")).isEqualTo(4);
    }

    @Test
    void updateInventory_writesInventoryUpdated_once() throws Exception {
        String sku = newSku();
        long id = insertItem(sku, 10);

        mockMvc.perform(put("/api/inventory/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content("{\"skuCode\":\"" + sku + "\",\"quantityOnHand\":20}"));

        var events = capture.withEvent("inventory_updated");
        assertThat(events).hasSize(1);
        assertAdminAudit(events.get(0), "inventory_updated", sku);
        assertThat(events.get(0).number("before")).isEqualTo(10);
        assertThat(events.get(0).number("after")).isEqualTo(20);
    }

    @Test
    void deleteInventory_writesInventoryDeleted_once() throws Exception {
        String sku = newSku();
        long id = insertItem(sku, 6);

        mockMvc.perform(delete("/api/inventory/{id}", id).header(HttpHeaders.AUTHORIZATION, ADMIN));

        var events = capture.withEvent("inventory_deleted");
        assertThat(events).hasSize(1);
        assertAdminAudit(events.get(0), "inventory_deleted", sku);
        assertThat(events.get(0).number("quantity")).isEqualTo(6);
    }

    @Test
    void aSkuWithControlCharacters_isCleanedInTheEvent_andStaysOneLine() throws Exception {
        // the path cannot carry a line break (Spring Security's firewall refuses it), but a JSON body can
        String sku = "LINE\r\nBREAK-" + COUNTER.incrementAndGet();

        MvcResult result = mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN)
                .content("{\"skuCode\":\"" + sku.replace("\r", "\\r").replace("\n", "\\n") + "\",\"quantityOnHand\":4}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var events = capture.withEvent("inventory_created");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).text("sku")).isEqualTo(sku.replace('\r', '_').replace('\n', '_'));
        assertThat(events.get(0).message()).doesNotContain("\r").doesNotContain("\n");
        for (LogCapture.Line line : capture.lines()) {
            assertThat(line.raw().stripTrailing()).as("one line per event").doesNotContain("\n").doesNotContain("\r");
            if (line.message().contains("LINE")) {
                assertThat(line.message()).doesNotContain("\n").doesNotContain("\r");
            }
        }
    }

    // ------------------------------------------------------------------ reserve and release (service token)

    @Test
    void reserve_writesStockReserved_once_andARepeatWritesNone() throws Exception {
        String sku = newSku();
        insertItem(sku, 10);

        reserve(sku, 3, 101);
        reserve(sku, 3, 101); // the same call again: safe to repeat, changes nothing, is not a new event

        var events = capture.withEvent("stock_reserved");
        assertThat(events).hasSize(1);
        assertServiceAudit(events.get(0), "stock_reserved", sku, 101);
        assertThat(events.get(0).level()).isEqualTo("INFO");
        assertThat(events.get(0).number("quantity")).isEqualTo(3);
        assertThat(events.get(0).number("after")).isEqualTo(7);
    }

    @Test
    void aRefusedReserve_writesAWarnEvent_andNoStockReserved() throws Exception {
        String sku = newSku();
        insertItem(sku, 2);

        reserve(sku, 5, 102);

        assertThat(capture.withEvent("stock_reserved")).isEmpty();
        var events = capture.withEvent("stock_reserve_refused");
        assertThat(events).hasSize(1);
        assertServiceAudit(events.get(0), "stock_reserve_refused", sku, 102);
        assertThat(events.get(0).level()).isEqualTo("WARN");
        assertThat(events.get(0).number("wanted")).isEqualTo(5);
        assertThat(events.get(0).number("available")).isEqualTo(2);
    }

    @Test
    void release_writesStockReleased_once_thenARepeatIsANoop() throws Exception {
        String sku = newSku();
        insertItem(sku, 10);
        reserve(sku, 3, 103);
        capture.clear();

        release(sku, 103);
        release(sku, 103);

        var released = capture.withEvent("stock_released");
        assertThat(released).hasSize(1);
        assertServiceAudit(released.get(0), "stock_released", sku, 103);
        assertThat(released.get(0).number("quantity")).isEqualTo(3);
        assertThat(released.get(0).number("after")).isEqualTo(10);

        var noops = capture.withEvent("stock_release_noop");
        assertThat(noops).hasSize(1);
        assertServiceAudit(noops.get(0), "stock_release_noop", sku, 103);
    }

    @Test
    void releaseOfSomethingNeverReserved_writesStockReleaseNoop() throws Exception {
        String sku = newSku();
        insertItem(sku, 10);

        release(sku, 104);

        assertThat(capture.withEvent("stock_released")).isEmpty();
        var noops = capture.withEvent("stock_release_noop");
        assertThat(noops).hasSize(1);
        assertServiceAudit(noops.get(0), "stock_release_noop", sku, 104);
    }

    // ------------------------------------------------------------------ failures write no audit event

    @Test
    void failedActions_writeNoAuditEvent() throws Exception {
        String sku = newSku();
        insertItem(sku, 2);
        PRODUCTS.respondWith(404, "{}"); // the catalog does not know the SKU

        mockMvc.perform(patch("/api/inventory/sku/NO-SUCH-SKU/adjust").param("delta", "1").header(HttpHeaders.AUTHORIZATION, ADMIN));
        mockMvc.perform(patch("/api/inventory/sku/{sku}/adjust", sku).param("delta", "-50").header(HttpHeaders.AUTHORIZATION, ADMIN));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, ADMIN)
                .content("{\"skuCode\":\"" + newSku() + "\",\"quantityOnHand\":4}"));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, ADMIN)
                .content("{}"));
        mockMvc.perform(put("/api/inventory/999999").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, ADMIN)
                .content("{\"skuCode\":\"X\",\"quantityOnHand\":1}"));
        mockMvc.perform(delete("/api/inventory/999999").header(HttpHeaders.AUTHORIZATION, ADMIN));
        mockMvc.perform(patch("/api/inventory/sku/{sku}/adjust", sku).param("delta", "1").header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("USER")));
        mockMvc.perform(post("/api/inventory/sku/NO-SUCH-SKU/reserve").param("quantity", "1").param("orderId", "5")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));
        mockMvc.perform(post("/api/inventory/sku/NO-SUCH-SKU/release").param("orderId", "5")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()));
        mockMvc.perform(post("/api/inventory/sku/{sku}/reserve", sku).param("quantity", "1").param("orderId", "5")
                .header(HttpHeaders.AUTHORIZATION, ADMIN)); // an admin may not reserve

        assertThat(capture.lines().stream().filter(line -> "audit".equals(line.logger()))).isEmpty();
    }
}
