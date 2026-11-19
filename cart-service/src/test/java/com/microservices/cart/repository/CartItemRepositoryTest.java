package com.microservices.cart.repository;

import com.microservices.cart.model.CartItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository test backed by a real embedded H2 database with Flyway running the real migration.
 */
@DataJpaTest
class CartItemRepositoryTest {

    @Autowired
    private CartItemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static CartItem item(long userId, String sku, int quantity) {
        return CartItem.builder().userId(userId).skuCode(sku).quantity(quantity).build();
    }

    @Test
    void save_assignsIdAndTimestamps() {
        CartItem saved = repository.saveAndFlush(item(1, "A-1", 2));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByUserIdOrderByIdAsc_returnsOnlyThatUsersItems_inTheOrderTheyWereAdded() {
        repository.save(item(1, "B-1", 1));
        repository.save(item(2, "X-1", 1));
        repository.save(item(1, "A-1", 1));
        repository.saveAndFlush(item(1, "C-1", 1));

        List<CartItem> items = repository.findByUserIdOrderByIdAsc(1);

        assertThat(items).extracting(CartItem::getSkuCode).containsExactly("B-1", "A-1", "C-1");
        assertThat(repository.findByUserIdOrderByIdAsc(3)).isEmpty();
    }

    @Test
    void findByUserIdAndSkuCode_findsOneItem() {
        repository.saveAndFlush(item(1, "A-1", 4));

        assertThat(repository.findByUserIdAndSkuCode(1, "A-1")).get().extracting(CartItem::getQuantity).isEqualTo(4);
        assertThat(repository.findByUserIdAndSkuCode(2, "A-1")).isEmpty();
        assertThat(repository.findByUserIdAndSkuCode(1, "a-1")).isEmpty();
    }

    @Test
    void sameSkuTwiceForOneUser_isRefusedByTheDatabase() {
        repository.saveAndFlush(item(1, "A-1", 1));

        assertThatThrownBy(() -> repository.saveAndFlush(item(1, "A-1", 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameSkuForTwoUsers_isFine() {
        repository.saveAndFlush(item(1, "A-1", 1));
        repository.saveAndFlush(item(2, "A-1", 1));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void deleteByUserIdAndSkuCode_removesOneItem_andCountsIt() {
        repository.save(item(1, "A-1", 1));
        repository.save(item(1, "B-1", 1));
        repository.saveAndFlush(item(2, "A-1", 1));

        assertThat(repository.deleteByUserIdAndSkuCode(1, "A-1")).isEqualTo(1);
        assertThat(repository.deleteByUserIdAndSkuCode(1, "A-1")).isZero();

        assertThat(repository.findByUserIdOrderByIdAsc(1)).extracting(CartItem::getSkuCode).containsExactly("B-1");
        assertThat(repository.findByUserIdOrderByIdAsc(2)).hasSize(1);
    }

    @Test
    void deleteByUserId_removesOnlyThatUsersItems() {
        repository.save(item(1, "A-1", 1));
        repository.save(item(1, "B-1", 1));
        repository.saveAndFlush(item(2, "A-1", 1));

        assertThat(repository.deleteByUserId(1)).isEqualTo(2);
        assertThat(repository.deleteByUserId(1)).isZero();

        assertThat(repository.findByUserIdOrderByIdAsc(1)).isEmpty();
        assertThat(repository.findByUserIdOrderByIdAsc(2)).hasSize(1);
    }

    // ---------------------------------------------------------------- the migration itself

    @Test
    void migration_createsTheTableFromTheContract() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'cart_items' "
                        + "ORDER BY ORDINAL_POSITION", String.class);

        assertThat(columns).containsExactly("id", "user_id", "sku_code", "quantity", "created_at", "updated_at");
    }

    @Test
    void database_refusesAQuantityOutsideOneTo99_whateverTheJavaCodeDoes() {
        for (int quantity : new int[] {0, 100, -1}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (1, 'A-1', ?)", quantity))
                    .as("quantity " + quantity)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbcTemplate.update("INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (1, 'A-1', 1)");
        jdbcTemplate.update("INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (1, 'A-2', 99)");
    }

    @Test
    void database_refusesAnEmptyColumn() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (NULL, 'A-1', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (1, NULL, 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (1, 'A-1', NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void skuCodeIsAtMost50Characters() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cart_items (user_id, sku_code, quantity) VALUES (1, ?, 1)", "S".repeat(51)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
