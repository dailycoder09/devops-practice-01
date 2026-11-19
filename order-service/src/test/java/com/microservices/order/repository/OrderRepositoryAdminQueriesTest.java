package com.microservices.order.repository;

import com.microservices.order.model.Order;
import com.microservices.order.model.OrderItem;
import com.microservices.order.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two queries behind the admin endpoints: orders of every user filtered by status, and the totals per status.
 */
@DataJpaTest
class OrderRepositoryAdminQueriesTest {

    @Autowired
    private OrderRepository orderRepository;

    private Order save(long userId, OrderStatus status, String total) {
        Order order = Order.builder().userId(userId).userEmail("u" + userId + "@example.com").status(status)
                .total(new BigDecimal(total)).build();
        order.addItem(OrderItem.builder().skuCode("SKU-A").name("Phone A").unitPrice(new BigDecimal("10.00"))
                .quantity(1).build());
        return orderRepository.saveAndFlush(order);
    }

    private static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    @Test
    void findByStatus_returnsTheOrdersOfEveryUserWithThatStatus_newestFirst() {
        orderRepository.deleteAll();
        Order first = save(1, OrderStatus.CONFIRMED, "10.00");
        save(2, OrderStatus.REJECTED, "20.00");
        Order third = save(3, OrderStatus.CONFIRMED, "30.00");
        save(4, OrderStatus.PENDING, "40.00");

        Page<Order> page = orderRepository.findByStatus(OrderStatus.CONFIRMED, newestFirst(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Order::getId).containsExactly(third.getId(), first.getId());
    }

    @Test
    void findByStatus_pagesAndCounts() {
        orderRepository.deleteAll();
        for (int i = 0; i < 5; i++) {
            save(i, OrderStatus.REJECTED, "1.00");
        }
        save(9, OrderStatus.CONFIRMED, "1.00");

        Page<Order> page = orderRepository.findByStatus(OrderStatus.REJECTED, newestFirst(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void totalsByStatus_countsAndSumsEachStatusThatExists() {
        orderRepository.deleteAll();
        save(1, OrderStatus.CONFIRMED, "100.00");
        save(2, OrderStatus.CONFIRMED, "61.50");
        save(3, OrderStatus.PENDING, "5.00");
        save(4, OrderStatus.REJECTED, "999.00");

        List<OrderRepository.StatusTotals> totals = orderRepository.totalsByStatus();

        assertThat(totals).hasSize(3);
        OrderRepository.StatusTotals confirmed = totals.stream()
                .filter(row -> row.getStatus() == OrderStatus.CONFIRMED).findFirst().orElseThrow();
        assertThat(confirmed.getOrders()).isEqualTo(2);
        assertThat(confirmed.getRevenue()).isEqualByComparingTo("161.50");
        assertThat(totals.stream().filter(row -> row.getStatus() == OrderStatus.PENDING).findFirst().orElseThrow()
                .getOrders()).isEqualTo(1);
        assertThat(totals.stream().filter(row -> row.getStatus() == OrderStatus.REJECTED).findFirst().orElseThrow()
                .getRevenue()).isEqualByComparingTo("999.00");
    }

    @Test
    void totalsByStatus_withoutOrders_hasNoRows() {
        orderRepository.deleteAll();

        assertThat(orderRepository.totalsByStatus()).isEmpty();
    }
}
