package com.microservices.product.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.product.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Test 7: the audit events of product-service, with their exact names and fields. The person who did it
 * (user id, email, role) comes from the MDC of the request. An event is written once per action, and
 * not at all when the action fails.
 */
class ProductAuditEventsTest extends AbstractLoggingIT {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String ADMIN = JwtTestSupport.bearerFor("9", "boss@example.com", "ADMIN");

    @Autowired
    private ObjectMapper objectMapper;

    private static String newSku() {
        return "AUDIT-" + COUNTER.incrementAndGet() + "-" + (System.nanoTime() % 100000);
    }

    private static String productJson(String sku, String name, String price) {
        return "{\"name\":\"" + name + "\",\"price\":" + price + ",\"category\":\"Electronics\",\"skuCode\":\"" + sku + "\"}";
    }

    private long create(String sku) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content(productJson(sku, "Audit Product", "10.50"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    private void assertAudit(LogCapture.Line line, String event, long productId, String sku) {
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.logger()).isEqualTo("audit");
        assertThat(line.text("event")).isEqualTo(event);
        assertThat(line.number("productId")).isEqualTo(productId);
        assertThat(line.text("sku")).isEqualTo(sku);
        assertThat(line.text("userId")).isEqualTo("9");
        assertThat(line.text("userEmail")).isEqualTo("boss@example.com");
        assertThat(line.text("userRole")).isEqualTo("ADMIN");
        assertThat(line.text("traceId")).isNotBlank();
        assertThat(line.message()).isNotBlank();
    }

    @Test
    void createProduct_writesProductCreated_once() throws Exception {
        String sku = newSku();

        long id = create(sku);

        var events = capture.withEvent("product_created");
        assertThat(events).hasSize(1);
        assertAudit(events.get(0), "product_created", id, sku);
    }

    @Test
    void updateProduct_writesProductUpdated_once() throws Exception {
        String sku = newSku();
        long id = create(sku);
        capture.clear();

        mockMvc.perform(put("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content(productJson(sku, "Renamed Product", "12.00")));

        var events = capture.withEvent("product_updated");
        assertThat(events).hasSize(1);
        assertAudit(events.get(0), "product_updated", id, sku);
    }

    @Test
    void replaceImages_writesProductImagesUpdated_once() throws Exception {
        String sku = newSku();
        long id = create(sku);
        capture.clear();

        mockMvc.perform(put("/api/products/{id}/images", id).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN)
                .content("{\"images\":[{\"url\":\"https://example.com/a.jpg\",\"alt\":\"A\"}]}"));

        var events = capture.withEvent("product_images_updated");
        assertThat(events).hasSize(1);
        assertAudit(events.get(0), "product_images_updated", id, sku);
    }

    @Test
    void deleteProduct_writesProductDeleted_once_withTheSkuOfTheDeletedProduct() throws Exception {
        String sku = newSku();
        long id = create(sku);
        capture.clear();

        mockMvc.perform(delete("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, ADMIN));

        var events = capture.withEvent("product_deleted");
        assertThat(events).hasSize(1);
        assertAudit(events.get(0), "product_deleted", id, sku);
    }

    @Test
    void failedActions_writeNoAuditEvent() throws Exception {
        String sku = newSku();
        long id = create(sku);
        capture.clear();

        // duplicate SKU (409), unknown id (404 on update, delete, images), invalid body (400), no right (403)
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content(productJson(sku, "Same Sku", "5.00")));
        mockMvc.perform(put("/api/products/999999").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content(productJson(newSku(), "Ghost Product", "5.00")));
        mockMvc.perform(delete("/api/products/999999").header(HttpHeaders.AUTHORIZATION, ADMIN));
        mockMvc.perform(put("/api/products/999999/images").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content("{\"images\":[]}"));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, ADMIN).content("{}"));
        mockMvc.perform(delete("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("USER")));

        assertThat(capture.lines().stream().filter(line -> "audit".equals(line.logger()))).isEmpty();
    }
}
