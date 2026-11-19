package com.microservices.product.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 points products at photos in S3 when the Flyway placeholder imageBaseUrl is set.
 * The expected folders in photo-folders.csv are built independently in Python (scripts/generate-photo-migration.py),
 * so this checks the SQL's folder derivation against a second implementation for all 5,010 products.
 */
@DataJpaTest(properties = "spring.flyway.placeholders.imageBaseUrl=https://img.test/")
class ProductPhotosMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    record Expected(String sku, String folder, boolean hasPhotos) {
    }

    private List<Expected> expected() throws Exception {
        List<Expected> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/photo-folders.csv"), StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                rows.add(new Expected(parts[0], parts[1], Boolean.parseBoolean(parts[2])));
            }
        }
        return rows;
    }

    @Test
    void everyProductWithPhotosGetsFourBucketUrls_andTheRestKeepTheirPlaceholders() throws Exception {
        Map<String, List<Map<String, Object>>> imagesBySku = jdbc.queryForList(
                        "SELECT p.sku_code AS sku, p.image_url AS main, pi.sort_order AS n, pi.url AS url "
                                + "FROM products p JOIN product_images pi ON pi.product_id = p.id ORDER BY p.sku_code, pi.sort_order")
                .stream().collect(Collectors.groupingBy(r -> (String) r.get("sku")));

        List<Expected> expected = expected();
        assertThat(expected).hasSize(5010);

        int withPhotos = 0;
        for (Expected e : expected) {
            List<Map<String, Object>> images = imagesBySku.get(e.sku());
            assertThat(images).as(e.sku()).hasSize(4);
            if (e.hasPhotos()) {
                withPhotos++;
                for (int n = 1; n <= 4; n++) {
                    assertThat(images.get(n - 1).get("url"))
                            .as(e.sku() + " image " + n)
                            .isEqualTo("https://img.test/products/" + e.folder() + "/" + n + ".jpg");
                }
                assertThat(images.get(0).get("main")).as(e.sku() + " main image").isEqualTo("https://img.test/products/" + e.folder() + "/1.jpg");
            } else {
                for (Map<String, Object> image : images) {
                    assertThat((String) image.get("url")).as(e.sku()).startsWith("https://placehold.co/");
                }
                assertThat((String) images.get(0).get("main")).as(e.sku()).startsWith("https://placehold.co/");
            }
        }
        assertThat(withPhotos).isGreaterThan(4000);
    }

    @Test
    void handWrittenProductsUseTheirOwnFolder() {
        assertThat(jdbc.queryForObject("SELECT image_url FROM products WHERE sku_code = 'APPLE-IP15P-128'", String.class))
                .isEqualTo("https://img.test/products/handwritten/iphone-15-pro/1.jpg");
    }

    @Test
    void thePlaceholderTablesAreRemoved() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) IN ('tmp_photo_groups', 'tmp_product_folder')",
                Long.class)).isZero();
    }
}
