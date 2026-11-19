package com.microservices.inventory.logging;

import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/inventory/low-stock (owner request): the records at or below their reorder threshold, lowest quantity
 * first, then SKU, as a Spring Page. Public like the other reads. (No equivalent endpoint existed before.)
 * The tests clean the tables first and build their own rows, so the seeded data cannot change the result.
 */
class LowStockEndpointTest extends AbstractLoggingIT {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTablesAndAddRows() {
        jdbc.update("DELETE FROM stock_reservations");
        jdbc.update("DELETE FROM inventory_audit_log");
        jdbc.update("DELETE FROM inventory_items");
        // sku, quantity, threshold
        insert("LOW-B", 5, 10);
        insert("LOW-F", 5, 10);   // same quantity as LOW-B: ordered by SKU
        insert("LOW-A", 0, 10);   // out of stock
        insert("LOW-E", 3, 3);    // exactly at the threshold
        insert("LOW-C", 10, 10);  // exactly at the default threshold
        insert("OK-D", 11, 10);   // one above: not low
        insert("OK-G", 500, 10);  // plenty
        insert("OK-H", 4, 3);     // one above its own threshold: not low
    }

    private void insert(String sku, int quantity, int threshold) {
        jdbc.update("INSERT INTO inventory_items (sku_code, quantity_on_hand, reorder_threshold) VALUES (?, ?, ?)",
                sku, quantity, threshold);
    }

    @Test
    void listsOnlyTheItemsAtOrBelowTheirThreshold_lowestQuantityFirst_thenSku() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.content[*].skuCode").value(contains("LOW-A", "LOW-E", "LOW-B", "LOW-F", "LOW-C")))
                .andExpect(jsonPath("$.content[*].quantityOnHand").value(contains(0, 3, 5, 5, 10)));
    }

    @Test
    void anItemIsTheUsualInventoryJson() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].skuCode").value("LOW-A"))
                .andExpect(jsonPath("$.content[0].quantityOnHand").value(0))
                .andExpect(jsonPath("$.content[0].reorderThreshold").value(10))
                .andExpect(jsonPath("$.content[0].createdAt").isString())
                .andExpect(jsonPath("$.content[0].updatedAt").isString());
    }

    @Test
    void theResponseIsASpringPage() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.numberOfElements").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/inventory/low-stock").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].skuCode").value(contains("LOW-C")))
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void theDefaultsArePage0AndSize20() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void aPageBeyondTheEnd_isEmpty_notAnError() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("page", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void whenNothingIsLow_theListIsEmpty() throws Exception {
        jdbc.update("DELETE FROM inventory_items WHERE quantity_on_hand <= reorder_threshold");

        mockMvc.perform(get("/api/inventory/low-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anItemThatIsRestocked_leavesTheList() throws Exception {
        jdbc.update("UPDATE inventory_items SET quantity_on_hand = 50 WHERE sku_code = 'LOW-A'");

        mockMvc.perform(get("/api/inventory/low-stock"))
                .andExpect(jsonPath("$.content[*].skuCode").value(contains("LOW-E", "LOW-B", "LOW-F", "LOW-C")));
    }

    // ------------------------------------------------------------------ role rules: public like the other GETs

    @Test
    void withoutAToken_isPublic() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void withAUserOrAdminToken_is200(String role) throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void withAServiceToken_is200() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").header(HttpHeaders.AUTHORIZATION, JwtTestSupport.serviceBearer()))
                .andExpect(status().isOk());
    }

    @Test
    void aBadToken_is401_likeEveryOtherRequestThatSendsOne() throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestSupport.expiredToken("ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ parameter limits

    @ParameterizedTest
    @ValueSource(strings = {"1", "20", "100"})
    void sizeFrom1To100_isAccepted(String size) throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("size", size)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "1000", "abc", "1.5", "99999999999"})
    void sizeOutsideTheLimits_is400(String size) throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "100000"})
    void pageFrom0To100000_isAccepted(String page) throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("page", page)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "100001", "abc", "99999999999"})
    void pageOutsideTheLimits_is400(String page) throws Exception {
        mockMvc.perform(get("/api/inventory/low-stock").param("page", page))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void theLiteralPath_isNotTakenForAnId() throws Exception {
        // GET /api/inventory/{id} wants a number; "low-stock" must reach the list, not fail as a bad id
        mockMvc.perform(get("/api/inventory/low-stock")).andExpect(jsonPath("$.content").isArray());
    }
}
