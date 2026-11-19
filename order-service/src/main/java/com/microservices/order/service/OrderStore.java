package com.microservices.order.service;

import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.dto.response.OrderStatsResponse;
import com.microservices.order.exception.OrderNotFoundException;
import com.microservices.order.mapper.OrderMapper;
import com.microservices.order.model.Order;
import com.microservices.order.model.OrderItem;
import com.microservices.order.model.OrderStatus;
import com.microservices.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Order Store
 *
 * Every database step of the checkout is its own small transaction here, and is committed when the method returns.
 * That matters: the order must be saved (and visible) BEFORE inventory-service is asked to reserve stock, and a
 * status change must not be undone by a later failure. The checkout itself ({@link com.microservices.order.service.impl.OrderServiceImpl})
 * is deliberately not one big transaction, because it waits on other services between the steps.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class OrderStore {

    private static final int MAX_REASON_LENGTH = 500;

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    /**
     * Save a new order as PENDING, with its lines and total (step 3 of the checkout)
     */
    @Transactional
    public OrderResponse createPending(long userId, String email, List<PricedLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        Order order = Order.builder()
                .userId(userId)
                .userEmail(email)
                .status(OrderStatus.PENDING)
                .build();
        for (PricedLine line : lines) {
            order.addItem(OrderItem.builder()
                    .skuCode(line.sku())
                    .name(line.name())
                    .unitPrice(line.unitPrice())
                    .quantity(line.quantity())
                    .build());
            total = total.add(OrderMapper.lineTotal(line.unitPrice(), line.quantity()));
        }
        order.setTotal(total);
        return orderMapper.toResponse(orderRepository.save(order));
    }

    /**
     * All stock is reserved: the order becomes CONFIRMED
     */
    @Transactional
    public OrderResponse markConfirmed(long orderId) {
        Order order = load(orderId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setRejectReason(null);
        return orderMapper.toResponse(order);
    }

    /**
     * The order becomes REJECTED, with the reason in plain words
     */
    @Transactional
    public void markRejected(long orderId, String reason) {
        Order order = load(orderId);
        order.setStatus(OrderStatus.REJECTED);
        order.setRejectReason(reason != null && reason.length() > MAX_REASON_LENGTH
                ? reason.substring(0, MAX_REASON_LENGTH) : reason);
    }

    /**
     * The caller's own orders, newest first
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> findOwn(long userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return orderRepository.findByUserId(userId, pageRequest).map(orderMapper::toResponse);
    }

    /**
     * One order, if it belongs to the caller or the caller is an admin. Otherwise the answer is the same as for
     * an order that does not exist, so a person cannot find out which ids are taken.
     */
    @Transactional(readOnly = true)
    public OrderResponse findVisible(long orderId, Caller caller) {
        return orderRepository.findById(orderId)
                .filter(order -> caller.admin() || order.getUserId().equals(caller.userId()))
                .map(orderMapper::toResponse)
                .orElseThrow(OrderNotFoundException::new);
    }

    /**
     * The orders of all users, newest first, optionally of one status (admin area)
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> findAll(OrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Order> orders = status == null
                ? orderRepository.findAll(pageRequest)
                : orderRepository.findByStatus(status, pageRequest);
        return orders.map(orderMapper::toResponse);
    }

    /**
     * Orders per status and the revenue of the confirmed orders (two decimals), from one query
     */
    @Transactional(readOnly = true)
    public OrderStatsResponse stats() {
        long pending = 0;
        long confirmed = 0;
        long rejected = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        for (OrderRepository.StatusTotals row : orderRepository.totalsByStatus()) {
            switch (row.getStatus()) {
                case PENDING -> pending = row.getOrders();
                case REJECTED -> rejected = row.getOrders();
                case CONFIRMED -> {
                    confirmed = row.getOrders();
                    revenue = row.getRevenue() == null ? BigDecimal.ZERO : row.getRevenue();
                }
            }
        }
        return new OrderStatsResponse(pending + confirmed + rejected, pending, confirmed, rejected,
                revenue.setScale(2, RoundingMode.HALF_UP));
    }

    private Order load(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " does not exist"));
    }
}
