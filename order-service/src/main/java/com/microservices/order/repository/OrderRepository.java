package com.microservices.order.repository;

import com.microservices.order.model.Order;
import com.microservices.order.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Order Repository
 *
 * @author Microservices Team
 * @version 1.0
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Find the orders of one user
     *
     * @param userId   the user id
     * @param pageable page, size and sort
     * @return a page of that user's orders
     */
    Page<Order> findByUserId(Long userId, Pageable pageable);

    /**
     * Find the orders of every user that have one status (admin area)
     *
     * @param status   the status
     * @param pageable page, size and sort
     * @return a page of orders with that status
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Count and total per status, in ONE query (so the numbers belong together). A status without orders has no row.
     *
     * @return one row for every status that has orders
     */
    @Query("select o.status as status, count(o) as orders, sum(o.total) as revenue from Order o group by o.status")
    List<StatusTotals> totalsByStatus();

    /**
     * One row of {@link #totalsByStatus()}
     */
    interface StatusTotals {

        OrderStatus getStatus();

        long getOrders();

        BigDecimal getRevenue();
    }
}
