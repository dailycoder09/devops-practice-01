package com.microservices.order.repository;

import com.microservices.order.model.Order;
import com.microservices.order.model.OrderItem;
import com.microservices.order.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository test backed by a real embedded H2 database with Flyway running the real migration
 * (and Hibernate checking that the entities match the tables).
 */
@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Order order(long userId, OrderStatus status, String total) {
        Order order = Order.builder()
                .userId(userId)
                .userEmail("u" + userId + "@example.com")
                .status(status)
                .total(new BigDecimal(total))
                .build();
        order.addItem(OrderItem.builder().skuCode("SKU-A").name("Phone A")
                .unitPrice(new BigDecimal("10.00")).quantity(2).build());
        return order;
    }

    @Test
    void save_assignsIdAndTimestamps_andSavesTheLinesWithTheOrder() {
        Order saved = orderRepository.saveAndFlush(order(1, OrderStatus.PENDING, "20.00"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Long.class,
                saved.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class,
                saved.getId())).isEqualByComparingTo("20.00");
    }

    @Test
    void findByUserId_returnsOnlyThatUsersOrders_newestFirst() {
        Order first = orderRepository.saveAndFlush(order(1, OrderStatus.CONFIRMED, "20.00"));
        orderRepository.saveAndFlush(order(2, OrderStatus.CONFIRMED, "30.00"));
        Order third = orderRepository.saveAndFlush(order(1, OrderStatus.REJECTED, "40.00"));

        Page<Order> page = orderRepository.findByUserId(1L,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Order::getId).containsExactly(third.getId(), first.getId());
    }

    @Test
    void findByUserId_pages() {
        for (int i = 0; i < 5; i++) {
            orderRepository.saveAndFlush(order(7, OrderStatus.CONFIRMED, "20.00"));
        }

        Page<Order> page = orderRepository.findByUserId(7L, PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void theEmailMayBeEmpty_andARejectReasonCanBeStored() {
        Order order = Order.builder().userId(3L).status(OrderStatus.REJECTED).total(BigDecimal.ZERO)
                .rejectReason("Not enough stock for SKU-A: wanted 3, available 1").build();

        Order saved = orderRepository.saveAndFlush(order);

        assertThat(orderRepository.findById(saved.getId())).get()
                .extracting(Order::getRejectReason).isEqualTo("Not enough stock for SKU-A: wanted 3, available 1");
    }

    @Test
    void databaseOnlyAllowsTheStatusesPendingConfirmedRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO orders (user_id, status, total) VALUES (1, 'SHIPPED', 1.00)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String status : List.of("PENDING", "CONFIRMED", "REJECTED")) {
            jdbcTemplate.update("INSERT INTO orders (user_id, status, total) VALUES (1, ?, 1.00)", status);
        }
    }

    @Test
    void anOrderLineMustPointToAnExistingOrder() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO order_items (order_id, sku_code, name, unit_price, quantity) "
                        + "VALUES (999999, 'SKU-A', 'x', 1.00, 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTablesHaveTheColumnsOfTheContract() {
        List<String> orderColumns = jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'orders'",
                String.class);
        List<String> itemColumns = jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'order_items'",
                String.class);

        assertThat(orderColumns).containsExactlyInAnyOrder("id", "user_id", "user_email", "status", "total",
                "reject_reason", "created_at", "updated_at");
        assertThat(itemColumns).containsExactlyInAnyOrder("id", "order_id", "sku_code", "name", "unit_price",
                "quantity");
    }
}
