package com.microservices.product.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** With no IMAGE_BASE_URL (the default), V5 must change nothing: every image keeps its placeholder URL. */
@DataJpaTest
class ProductPhotosMigrationDisabledTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void withoutABucketAddressEveryImageKeepsItsPlaceholder() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images WHERE url NOT LIKE 'https://placehold.co/%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE image_url NOT LIKE 'https://placehold.co/%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images", Long.class)).isEqualTo(5010L * 4);
    }
}
